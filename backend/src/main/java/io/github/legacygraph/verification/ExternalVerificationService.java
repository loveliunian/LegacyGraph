package io.github.legacygraph.verification;

import io.github.legacygraph.extractors.adapter.ScanContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;

/**
 * 外部验证编排服务，协调多个验证适配器的执行。
 * <p>
 * 职责：收集所有健康的 {@link ExternalVerificationAdapter}，并行执行验证，
 * 收集结果后调用 {@link ResultFusionEngine} 融合写入图谱。
 * </p>
 */
@Service
@Slf4j
public class ExternalVerificationService {

    private final List<ExternalVerificationAdapter> adapters;
    private final ResultFusionEngine resultFusionEngine;

    @Value("${legacygraph.external-verification.timeout-seconds:60}")
    private int timeoutSeconds = 60;

    public ExternalVerificationService(List<ExternalVerificationAdapter> adapters,
                                       ResultFusionEngine resultFusionEngine) {
        this.adapters = new ArrayList<>(adapters);
        this.adapters.sort(Comparator.comparingInt(ExternalVerificationAdapter::priority));
        this.resultFusionEngine = resultFusionEngine;
        log.info("ExternalVerificationService initialized with {} adapters: {}",
                adapters.size(),
                adapters.stream().map(ExternalVerificationAdapter::adapterName).toList());
    }

    /**
     * 执行外部验证。
     *
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @param context   扫描上下文
     * @return 融合统计
     */
    public ResultFusionEngine.FusionStats executeVerification(String projectId, String versionId, ScanContext context) {
        // 1. 筛选健康的适配器
        List<ExternalVerificationAdapter> healthyAdapters = new ArrayList<>();
        for (ExternalVerificationAdapter adapter : adapters) {
            try {
                if (adapter.supports(context) && adapter.checkHealth()) {
                    healthyAdapters.add(adapter);
                } else {
                    log.warn("适配器 {} 不可用或不支持当前上下文，跳过", adapter.adapterName());
                }
            } catch (Exception e) {
                log.warn("适配器 {} 健康检查异常，跳过: {}", adapter.adapterName(), e.getMessage());
            }
        }

        if (healthyAdapters.isEmpty()) {
            log.info("无可用的外部验证适配器，跳过 EXTERNAL_VERIFY 阶段");
            return ResultFusionEngine.FusionStats.builder()
                    .confirmedCount(0)
                    .missingWritten(0)
                    .propertiesWritten(0)
                    .suspiciousMarked(0)
                    .errors(0)
                    .build();
        }

        log.info("开始外部验证，{} 个适配器可用: {}",
                healthyAdapters.size(),
                healthyAdapters.stream().map(ExternalVerificationAdapter::adapterName).toList());

        // 2. 并行执行验证（虚拟线程）
        List<VerificationResult> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<VerificationResult>> futures = new ArrayList<>();
            for (ExternalVerificationAdapter adapter : healthyAdapters) {
                futures.add(executor.submit(() -> verifyWithTimeout(adapter, projectId, versionId, context)));
            }

            for (int i = 0; i < futures.size(); i++) {
                ExternalVerificationAdapter adapter = healthyAdapters.get(i);
                try {
                    VerificationResult result = futures.get(i).get(timeoutSeconds, TimeUnit.SECONDS);
                    if (result != null) {
                        results.add(result);
                        log.info("适配器 {} 验证完成: 检查 {} 项, 确认 {} 项, 缺失 {} 项, 可疑 {} 项",
                                adapter.adapterName(),
                                result.getTotalChecked(),
                                result.getTotalConfirmed(),
                                result.getMissingEdges().size(),
                                result.getSuspiciousEdges().size());
                    }
                } catch (TimeoutException e) {
                    log.warn("适配器 {} 验证超时（{}秒），跳过", adapter.adapterName(), timeoutSeconds);
                    futures.get(i).cancel(true);
                } catch (ExecutionException e) {
                    log.warn("适配器 {} 验证异常: {}", adapter.adapterName(), e.getCause().getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("外部验证被中断");
                    break;
                }
            }
        }

        // 3. 融合结果
        if (results.isEmpty()) {
            log.info("所有适配器均未返回验证结果");
            return ResultFusionEngine.FusionStats.builder()
                    .confirmedCount(0)
                    .missingWritten(0)
                    .propertiesWritten(0)
                    .suspiciousMarked(0)
                    .errors(0)
                    .build();
        }

        log.info("开始融合 {} 个验证结果", results.size());
        ResultFusionEngine.FusionStats stats = resultFusionEngine.fuse(projectId, versionId, results);
        log.info("外部验证融合完成: 确认 {} 条, 补写 {} 条, 属性 {} 个, 标记可疑 {} 条, 错误 {} 个",
                stats.getConfirmedCount(),
                stats.getMissingWritten(),
                stats.getPropertiesWritten(),
                stats.getSuspiciousMarked(),
                stats.getErrors());

        return stats;
    }

    /**
     * 带异常隔离的验证执行
     */
    private VerificationResult verifyWithTimeout(ExternalVerificationAdapter adapter,
                                                  String projectId, String versionId, ScanContext context) {
        try {
            return adapter.verify(projectId, versionId, context);
        } catch (Exception e) {
            log.warn("适配器 {} verify 异常: {}", adapter.adapterName(), e.getMessage());
            return VerificationResult.empty(adapter.adapterName());
        }
    }

    /**
     * 检查是否有任何可用的验证适配器
     */
    public boolean hasAvailableAdapters(ScanContext context) {
        for (ExternalVerificationAdapter adapter : adapters) {
            try {
                if (adapter.supports(context) && adapter.checkHealth()) {
                    return true;
                }
            } catch (Exception e) {
                // 忽略，继续检查下一个
            }
        }
        return false;
    }
}
