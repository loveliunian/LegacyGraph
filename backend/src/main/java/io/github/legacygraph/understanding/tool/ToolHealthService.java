package io.github.legacygraph.understanding.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 工具健康检查服务 —— 统一健康检查入口。
 *
 * <p>职责：
 * <ul>
 *   <li>按 projectId + toolName 检查单个工具健康状态</li>
 *   <li>按 projectId 检查所有已注册工具的健康状态</li>
 *   <li>状态判定逻辑：命令不存在 → NOT_INSTALLED，连接失败 → UNAVAILABLE，索引过期 → STALE，否则 → READY</li>
 *   <li>禁用的工具不参与健康检查</li>
 * </ul>
 */
@Slf4j
@Service
public class ToolHealthService {

    private final ToolRegistry registry;

    public ToolHealthService(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 检查单个工具的健康状态。
     *
     * @param projectId 项目 ID（用于构建 ToolContext）
     * @param toolName  工具名称
     * @return 工具健康状态结果
     */
    public ToolHealth checkHealth(String projectId, String toolName) {
        // 1. 查找适配器
        Optional<CodeUnderstandingToolAdapter> adapterOpt = registry.getAdapter(toolName);

        if (adapterOpt.isEmpty()) {
            // 工具未注册（可能已禁用或不存在）
            log.warn("工具 [{}] 未注册（可能已禁用或不存在）", toolName);
            return ToolHealth.builder()
                    .toolName(toolName)
                    .toolKind(ToolKind.LOCAL)
                    .status(ToolStatus.NOT_INSTALLED)
                    .capabilities(Collections.emptySet())
                    .indexFreshness("UNKNOWN")
                    .message("工具未注册或已禁用")
                    .build();
        }

        // 2. 检查配置是否禁用
        Optional<ToolConfigProperties.ToolConfig> configOpt = registry.getToolConfig(toolName);
        if (configOpt.isPresent() && !configOpt.get().isEnabled()) {
            log.info("工具 [{}] 已在配置中禁用，跳过健康检查", toolName);
            return ToolHealth.builder()
                    .toolName(toolName)
                    .toolKind(configOpt.get().getKind())
                    .status(ToolStatus.NOT_INSTALLED)
                    .capabilities(Collections.emptySet())
                    .indexFreshness("UNKNOWN")
                    .message("工具已禁用")
                    .build();
        }

        // 3. 构建上下文并调用适配器健康检查
        CodeUnderstandingToolAdapter adapter = adapterOpt.get();
        ToolContext context = ToolContext.builder()
                .projectId(projectId)
                .projectRoot(null)  // 健康检查不强制要求 projectRoot
                .workspaceWhitelist(Collections.emptyList())
                .build();

        try {
            ToolHealth health = adapter.checkHealth(context);
            log.debug("工具 [{}] 健康状态: {}", toolName, health.getStatus());
            return health;
        } catch (Exception e) {
            // 健康检查异常视为不可用
            log.error("工具 [{}] 健康检查异常", toolName, e);
            return ToolHealth.builder()
                    .toolName(toolName)
                    .toolKind(adapter.toolKind())
                    .status(ToolStatus.UNAVAILABLE)
                    .capabilities(adapter.capabilities())
                    .indexFreshness("UNKNOWN")
                    .message("健康检查异常: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 检查所有已注册工具的健康状态。
     *
     * @param projectId 项目 ID
     * @return 所有工具的健康状态列表
     */
    public List<ToolHealth> checkAllTools(String projectId) {
        List<CodeUnderstandingToolAdapter> adapters = registry.listAdapters();

        if (adapters.isEmpty()) {
            log.warn("没有已注册的工具适配器");
            return Collections.emptyList();
        }

        List<ToolHealth> results = new ArrayList<>(adapters.size());
        for (CodeUnderstandingToolAdapter adapter : adapters) {
            String toolName = adapter.toolName();

            // 跳过禁用的工具
            Optional<ToolConfigProperties.ToolConfig> configOpt = registry.getToolConfig(toolName);
            if (configOpt.isPresent() && !configOpt.get().isEnabled()) {
                log.debug("工具 [{}] 已禁用，跳过批量健康检查", toolName);
                continue;
            }

            results.add(checkHealth(projectId, toolName));
        }

        log.info("已完成 {} 个工具的健康检查", results.size());
        return Collections.unmodifiableList(results);
    }
}
