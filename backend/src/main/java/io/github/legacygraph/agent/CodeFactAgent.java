package io.github.legacygraph.agent;

import io.github.legacygraph.dto.FactExtractionResult;
import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CodeFactAgent - 理解代码事实的业务语义
 *
 * 职责：
 * - 理解方法业务语义
 * - 补全动态 SQL 分支
 * - 解释复杂调用链
 */
@Slf4j
@Service
@org.springframework.context.annotation.Lazy(false)
@RequiredArgsConstructor
public class CodeFactAgent {

    private final LlmGateway llmGateway;

    /**
     * 处理代码片段，提取结构化事实
     */
    public FactExtractionResult extractFacts(String projectId, String codeContent, String sourcePath) {
        Map<String, String> variables = new HashMap<>();
        variables.put("projectId", projectId != null ? projectId : "");
        variables.put("codeContent", codeContent);
        variables.put("sourcePath", sourcePath);

        return llmGateway.callWithTemplate(projectId, "code-fact-extraction",
                variables, FactExtractionResult.class);
    }

    /**
     * 将代码语义抽取结果转换为 Feature 实现 Claim。
     */
    public List<KnowledgeClaimDraft> toClaimDrafts(String projectId, String versionId,
                                                   FactExtractionResult result,
                                                   String sourcePath) {
        List<KnowledgeClaimDraft> drafts = new ArrayList<>();
        if (result == null || result.getItems() == null) {
            return drafts;
        }
        for (FactExtractionResult.FactItem item : result.getItems()) {
            if (item == null || !hasText(item.getName())) {
                continue;
            }
            Map<String, Object> qualifiers = new HashMap<>();
            if (hasText(item.getKey())) {
                qualifiers.put("sourceFactKey", item.getKey());
            }
            qualifiers.put("sourcePath", sourcePath);
            drafts.add(KnowledgeClaimDraft.builder()
                    .projectId(projectId)
                    .versionId(versionId)
                    .subjectType("Feature")
                    .subjectKey("feature:" + item.getName())
                    .predicate("IMPLEMENTS")
                    .objectType("SourceFile")
                    .objectKey(sourcePath)
                    .qualifiers(qualifiers)
                    .sourceType("CODE_AI")
                    .extractor("CodeFactAgent")
                    .confidence(item.getConfidence() != null ? item.getConfidence() : BigDecimal.valueOf(0.7))
                    .evidenceIds(hasText(sourcePath) ? List.of(sourcePath) : List.of())
                    .build());
        }
        return drafts;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
