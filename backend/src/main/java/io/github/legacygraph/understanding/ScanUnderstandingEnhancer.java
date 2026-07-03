package io.github.legacygraph.understanding;

import io.github.legacygraph.dto.understanding.CodeUnderstandingRequest;
import io.github.legacygraph.dto.understanding.CodeUnderstandingTaskResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 扫描后代码理解增强器 —— 在扫描完成后，对关键目标执行代码理解增强。
 *
 * <p>行为：
 * <ul>
 *   <li>按复杂度、入度、缺口数量、业务重要性选择目标</li>
 *   <li>默认每次扫描最多 20 个目标</li>
 *   <li>对每个目标调用只读编排，生成候选 Claim 和模块文档草稿</li>
 *   <li>工具失败不影响基础扫描状态</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanUnderstandingEnhancer {

    private final CodeUnderstandingOrchestrator orchestrator;

    /** 默认关闭，通过配置 legacygraph.understanding.scan-enhancement.enabled 开启 */
    @Value("${legacygraph.understanding.scan-enhancement.enabled:false}")
    private boolean enhancementEnabled;

    /** 每次扫描最大增强目标数 */
    @Value("${legacygraph.understanding.scan-enhancement.max-targets:20}")
    private int maxTargets;

    /**
     * 对扫描结果执行代码理解增强。
     *
     * @param projectId  项目 ID
     * @param versionId  扫描版本 ID
     * @param topSymbols 优先级最高的符号列表（按复杂度/入度/缺口排序）
     * @return 增强结果摘要
     */
    public EnhancementResult enhance(String projectId, String versionId, List<String> topSymbols) {
        if (!enhancementEnabled) {
            log.info("扫描后增强未启用: projectId={}", projectId);
            return EnhancementResult.builder()
                    .enabled(false)
                    .message("扫描后增强未启用")
                    .build();
        }

        if (topSymbols == null || topSymbols.isEmpty()) {
            log.info("无增强目标: projectId={}", projectId);
            return EnhancementResult.builder()
                    .enabled(true)
                    .enhancedCount(0)
                    .message("无增强目标")
                    .build();
        }

        int targetCount = Math.min(topSymbols.size(), maxTargets);
        int successCount = 0;
        int failCount = 0;
        int evidenceTotal = 0;

        for (int i = 0; i < targetCount; i++) {
            String symbol = topSymbols.get(i);
            try {
                CodeUnderstandingRequest request = CodeUnderstandingRequest.builder()
                        .versionId(versionId)
                        .question("分析符号 " + symbol + " 的代码结构、调用链路和业务关系")
                        .scope(CodeUnderstandingRequest.Scope.builder()
                                .symbols(List.of(symbol))
                                .build())
                        .reportType("CODE_UNDERSTANDING")
                        .format("MD")
                        .toolPolicy(new CodeUnderstandingRequest.ToolPolicyDto())
                        .build();

                CodeUnderstandingTaskResult result = orchestrator.execute(projectId, request);
                successCount++;
                evidenceTotal += result.getEvidenceCount();
                log.info("增强成功: {}/{} symbol={}, evidence={}",
                        i + 1, targetCount, symbol, result.getEvidenceCount());
            } catch (Exception e) {
                failCount++;
                log.error("增强失败: symbol={}, error={}", symbol, e.getMessage());
                // 不阻断后续目标
            }
        }

        log.info("扫描后增强完成: success={}, fail={}, totalEvidence={}",
                successCount, failCount, evidenceTotal);

        return EnhancementResult.builder()
                .enabled(true)
                .enhancedCount(successCount)
                .failCount(failCount)
                .totalEvidence(evidenceTotal)
                .message(String.format("增强 %d/%d 个目标成功，共收集 %d 条证据",
                        successCount, targetCount, evidenceTotal))
                .build();
    }

    /**
     * 增强结果 DTO。
     */
    @lombok.Builder
    @lombok.Data
    public static class EnhancementResult {
        private boolean enabled;
        private int enhancedCount;
        private int failCount;
        private int totalEvidence;
        private String message;
    }
}
