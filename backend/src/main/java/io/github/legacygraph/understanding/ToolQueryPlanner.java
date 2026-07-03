package io.github.legacygraph.understanding;

import io.github.legacygraph.understanding.tool.ToolCapability;
import io.github.legacygraph.understanding.tool.ToolRequest;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 工具查询计划器 —— 将用户问题和分析范围转换为工具能力需求序列。
 *
 * <p>负责生成分步的工具调用计划：
 * <ol>
 *   <li>根据问题拆解为子问题</li>
 *   <li>每个子问题映射到工具能力需求</li>
 *   <li>按优先级排序（结构化查询 → 深读 → 摘要）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolQueryPlanner {

    /**
     * 根据用户请求生成工具查询计划。
     */
    public ToolQueryPlan plan(CodeUnderstandingRequestWrapper request) {
        String question = request.getQuestion();
        List<String> paths = request.getScopePaths();
        List<String> symbols = request.getScopeSymbols();
        List<String> featureKeys = request.getScopeFeatureKeys();

        List<PlanStep> steps = new ArrayList<>();

        // 阶段 1：结构化查询（MCP / 本地图谱）
        // 1.1 搜索指定符号
        if (symbols != null && !symbols.isEmpty()) {
            for (String symbol : symbols) {
                steps.add(PlanStep.builder()
                        .phase("STRUCTURED_QUERY")
                        .capability(ToolCapability.SEARCH_SYMBOL)
                        .description("搜索符号: " + symbol)
                        .parameters(Map.of("symbol", symbol))
                        .priority(1)
                        .build());
            }
        }

        // 1.2 追踪调用链
        if (question != null && (question.contains("调用") || question.contains("链路") || question.contains("依赖"))) {
            steps.add(PlanStep.builder()
                    .phase("STRUCTURED_QUERY")
                    .capability(ToolCapability.TRACE_CALL)
                    .description("追踪调用链")
                    .parameters(Map.of("question", question))
                    .priority(2)
                    .build());
        }

        // 阶段 2：深读关键材料
        if (paths != null && !paths.isEmpty()) {
            for (String path : paths) {
                steps.add(PlanStep.builder()
                        .phase("DEEP_READ")
                        .capability(ToolCapability.READ_SNIPPET)
                        .description("读取文件: " + path)
                        .parameters(Map.of("path", path))
                        .priority(3)
                        .build());
            }
        }

        // 阶段 3：功能切片查询
        if (featureKeys != null && !featureKeys.isEmpty()) {
            for (String featureKey : featureKeys) {
                steps.add(PlanStep.builder()
                        .phase("FEATURE_QUERY")
                        .capability(ToolCapability.SEARCH_SYMBOL)
                        .description("查询功能: " + featureKey)
                        .parameters(Map.of("featureKey", featureKey))
                        .priority(4)
                        .build());
            }
        }

        // 阶段 4：摘要
        if (steps.size() > 3) {
            steps.add(PlanStep.builder()
                    .phase("SUMMARY")
                    .capability(ToolCapability.SUMMARIZE)
                    .description("生成分析摘要")
                    .parameters(Map.of("question", question))
                    .priority(5)
                    .build());
        }

        log.info("工具查询计划生成完成: steps={}", steps.size());
        return new ToolQueryPlan(steps);
    }

    /**
     * 将请求包装为内部使用的格式。
     */
    @Data
    @Builder
    public static class CodeUnderstandingRequestWrapper {
        private String question;
        private List<String> scopePaths;
        private List<String> scopeSymbols;
        private List<String> scopeFeatureKeys;
        private int maxToolRuns;
    }

    /**
     * 查询计划步骤。
     */
    @Data
    @Builder
    public static class PlanStep {
        /** 阶段：STRUCTURED_QUERY / DEEP_READ / FEATURE_QUERY / SUMMARY */
        private String phase;
        /** 需要的工具能力 */
        private ToolCapability capability;
        /** 步骤描述 */
        private String description;
        /** 参数 */
        private Map<String, Object> parameters;
        /** 优先级（越小越高） */
        private int priority;
    }

    /**
     * 工具查询计划。
     */
    public record ToolQueryPlan(List<PlanStep> steps) {
        public boolean isEmpty() {
            return steps == null || steps.isEmpty();
        }

        public PlanStep next() {
            return steps.isEmpty() ? null : steps.get(0);
        }
    }
}
