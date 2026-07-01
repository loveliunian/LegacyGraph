package io.github.legacygraph.agent;

import io.github.legacygraph.dto.ReportInsight;
import io.github.legacygraph.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * ReportInsightAgent - 报告洞察与行动建议生成器
 *
 * <p>输入图谱指标 + 低置信/孤立/未覆盖摘要，输出按优先级排序、可执行、带来源的行动清单，
 * 把报告从"指标展示"提升为"下一步工作队列"。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportInsightAgent {

    private final LlmGateway llmGateway;

    /**
     * 生成报告行动建议
     *
     * @param projectId  项目ID
     * @param metricsJson 报告指标（JSON 文本）
     * @param gapsJson    低置信/孤立/未覆盖摘要（JSON 文本）
     */
    public ReportInsight generateInsights(String projectId, String metricsJson, String gapsJson) {
        Map<String, String> variables = new HashMap<>();
        variables.put("metrics", metricsJson != null ? metricsJson : "{}");
        variables.put("gaps", gapsJson != null ? gapsJson : "{}");

        return llmGateway.callWithTemplate(projectId, "report-insight", variables, ReportInsight.class);
    }
}
