package io.github.legacygraph.service.requirement;

import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.llm.LlmGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Map;

/**
 * 需求结构化抽取服务（Task 6）。
 * <p>调用 LLM 对需求文本做结构化抽取，输出 {@link RequirementAnalysis}。
 * system prompt 约束不补造信息，缺失写入 openQuestions。</p>
 */
@Slf4j
@Service
public class RequirementExtractionService {

    /** prompt 模板名，对应 classpath:/prompts/requirement-analysis.txt */
    static final String TEMPLATE_NAME = "requirement-analysis";

    private final LlmGateway llmGateway;

    public RequirementExtractionService(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    /**
     * 结构化抽取需求文本。
     *
     * @param projectId       项目 ID
     * @param requirementText 需求原文
     * @return 抽取结果（goal / items / openQuestions）
     */
    public RequirementAnalysis extract(String projectId, String requirementText) {
        if (requirementText == null || requirementText.isBlank()) {
            throw new IllegalArgumentException("需求文本不能为空");
        }
        Map<String, String> variables = Map.of("requirementText", requirementText);
        log.info("Requirement extraction started: projectId={}, textLen={}", projectId, requirementText.length());

        RequirementAnalysis analysis = llmGateway.callWithTemplate(
                projectId, TEMPLATE_NAME, variables, RequirementAnalysis.class);

        if (analysis == null) {
            analysis = new RequirementAnalysis();
        }
        // 防御性兜底：LLM 返回缺字段时补默认空集合
        if (analysis.getItems() == null) {
            analysis.setItems(new ArrayList<>());
        }
        if (analysis.getOpenQuestions() == null) {
            analysis.setOpenQuestions(new ArrayList<>());
        }
        // 校正：items 中空集合兜底，避免后续 NPE
        analysis.getItems().forEach(item -> {
            if (item.getAcceptanceCriteria() == null) {
                item.setAcceptanceCriteria(new ArrayList<>());
            }
            if (item.getConstraints() == null) {
                item.setConstraints(new ArrayList<>());
            }
        });

        log.info("Requirement extraction completed: projectId={}, items={}, openQuestions={}",
                projectId, analysis.getItems().size(), analysis.getOpenQuestions().size());
        return analysis;
    }
}
