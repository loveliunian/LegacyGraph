package io.github.legacygraph.plugin;

import io.github.legacygraph.dto.plugin.ExternalPluginDescriptor;
import io.github.legacygraph.extractors.adapter.ScanContext;
import io.github.legacygraph.verification.ResultFusionEngine;
import io.github.legacygraph.verification.VerificationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;

/**
 * 插件调用编排服务，协调用户选择的插件在扫描流程中执行。
 * <p>
 * 职责：从所有 {@link PluginAdapter} Bean 中筛选用户通过 pluginIds 指定的插件，
 * 健康检查通过后并行执行，收集结果后调用 {@link ResultFusionEngine} 融合写入图谱。
 * </p>
 * <p>
 * 支持两类插件适配器：
 * <ul>
 *   <li>固定 pluginId 的 {@link PluginAdapter} Bean（如 McpPluginAdapter 之外的定制适配器）</li>
 *   <li>动态的 {@link McpPluginAdapter}（多合一，从 PluginRegistry 查找外部 MCP 插件端点）</li>
 * </ul>
 * </p>
 * <p>
 * 容错策略与 ExternalVerificationService 一致：单个插件失败不阻塞整体，失败计入 errors。
 * </p>
 */
@Service
@Slf4j
public class PluginInvocationService {

    private final List<PluginAdapter> adapters;
    private final PluginRegistry pluginRegistry;
    private final McpPluginAdapter mcpPluginAdapter;
    private final ResultFusionEngine resultFusionEngine;

    @Value("${legacygraph.plugin.timeout-seconds:60}")
    private int timeoutSeconds = 60;

    public PluginInvocationService(List<PluginAdapter> adapters,
                                    PluginRegistry pluginRegistry,
                                    McpPluginAdapter mcpPluginAdapter,
                                    ResultFusionEngine resultFusionEngine) {
        this.adapters = new ArrayList<>(adapters);
        this.adapters.sort(Comparator.comparingInt(PluginAdapter::priority));
        this.pluginRegistry = pluginRegistry;
        this.mcpPluginAdapter = mcpPluginAdapter;
        this.resultFusionEngine = resultFusionEngine;
        log.info("PluginInvocationService initialized with {} adapters + McpPluginAdapter",
                adapters.size());
    }

    /**
     * 执行用户选择的插件调用。
     *
     * @param projectId  项目 ID
     * @param versionId  版本 ID
     * @param pluginIds  用户选择的插件 ID 列表
     * @param context    扫描上下文
     * @return 融合统计
     */
    public ResultFusionEngine.FusionStats invokePlugins(String projectId,
                                                         String versionId,
                                                         List<String> pluginIds,
                                                         ScanContext context) {
        if (pluginIds == null || pluginIds.isEmpty()) {
            log.info("未选择任何插件，跳过 PLUGIN_INVOKE 阶段");
            return emptyStats();
        }

        // 1. 构建调用任务列表：固定适配器 + 动态 MCP 插件
        List<PluginCallTask> callTasks = new ArrayList<>();

        // 1a. 固定 pluginId 的适配器
        for (PluginAdapter adapter : adapters) {
            String adapterId = adapter.pluginId();
            if (adapterId == null || !pluginIds.contains(adapterId)) {
                continue;
            }
            try {
                if (adapter.supports(context) && adapter.checkHealth()) {
                    callTasks.add(new PluginCallTask(adapter.pluginId(),
                            () -> safeInvoke(adapter, projectId, versionId, context)));
                } else {
                    log.warn("插件适配器 {} 不可用或不支持当前上下文，跳过", adapter.pluginId());
                }
            } catch (Exception e) {
                log.warn("插件适配器 {} 健康检查异常，跳过: {}", adapter.pluginId(), e.getMessage());
            }
        }

        // 1b. 动态 MCP 插件（用户选择了在 Registry 中注册的外部插件）
        for (String pluginId : pluginIds) {
            ExternalPluginDescriptor ext = pluginRegistry.getExternal(pluginId);
            if (ext == null) {
                continue; // 不是外部注册的插件，可能已被固定适配器处理
            }
            if (mcpPluginAdapter.supports(context) && mcpPluginAdapter.checkHealth()) {
                callTasks.add(new PluginCallTask(pluginId,
                        () -> mcpPluginAdapter.invokeForPlugin(pluginId, projectId, versionId, context)));
            } else {
                log.warn("MCP 插件 {} 适配器不可用，跳过", pluginId);
            }
        }

        if (callTasks.isEmpty()) {
            log.warn("用户选择了 {} 个插件，但没有可用的适配器: {}", pluginIds.size(), pluginIds);
            return emptyStats();
        }

        log.info("开始执行插件调用，{} 个插件可用: {}",
                callTasks.size(),
                callTasks.stream().map(PluginCallTask::pluginId).toList());

        // 2. 并行执行（虚拟线程）
        List<VerificationResult> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<VerificationResult>> futures = new ArrayList<>();
            for (PluginCallTask task : callTasks) {
                futures.add(executor.submit(task.callable()));
            }

            for (int i = 0; i < futures.size(); i++) {
                String pluginId = callTasks.get(i).pluginId();
                try {
                    VerificationResult result = futures.get(i).get(timeoutSeconds, TimeUnit.SECONDS);
                    if (result != null) {
                        results.add(result);
                        log.info("插件 {} 调用完成: 检查 {} 项, 确认 {} 项, 补漏 {} 项, 可疑 {} 项",
                                pluginId,
                                result.getTotalChecked(),
                                result.getTotalConfirmed(),
                                result.getMissingEdges().size(),
                                result.getSuspiciousEdges().size());
                    }
                } catch (TimeoutException e) {
                    log.warn("插件 {} 调用超时（{}秒），跳过", pluginId, timeoutSeconds);
                    futures.get(i).cancel(true);
                } catch (ExecutionException e) {
                    log.warn("插件 {} 调用异常: {}", pluginId, e.getCause().getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("插件调用被中断");
                    break;
                }
            }
        }

        // 3. 融合结果
        if (results.isEmpty()) {
            log.info("所有插件均未返回结果");
            return emptyStats();
        }

        log.info("开始融合 {} 个插件结果", results.size());
        ResultFusionEngine.FusionStats stats = resultFusionEngine.fuse(projectId, versionId, results);
        log.info("插件调用融合完成: 确认 {} 条, 补写 {} 条, 属性 {} 个, 标记可疑 {} 条, 错误 {} 个",
                stats.getConfirmedCount(),
                stats.getMissingWritten(),
                stats.getPropertiesWritten(),
                stats.getSuspiciousMarked(),
                stats.getErrors());
        return stats;
    }

    private VerificationResult safeInvoke(PluginAdapter adapter,
                                            String projectId, String versionId, ScanContext context) {
        try {
            return adapter.invoke(projectId, versionId, context);
        } catch (Exception e) {
            log.warn("插件 {} invoke 异常: {}", adapter.pluginId(), e.getMessage());
            return VerificationResult.empty(adapter.pluginId());
        }
    }

    private ResultFusionEngine.FusionStats emptyStats() {
        return ResultFusionEngine.FusionStats.builder()
                .confirmedCount(0)
                .missingWritten(0)
                .propertiesWritten(0)
                .suspiciousMarked(0)
                .errors(0)
                .build();
    }

    /** 调用任务记录 */
    private record PluginCallTask(String pluginId, Callable<VerificationResult> callable) {}
}
