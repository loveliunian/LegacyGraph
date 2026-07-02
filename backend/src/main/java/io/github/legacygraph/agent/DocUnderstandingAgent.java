package io.github.legacygraph.agent;

import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DocUnderstandingAgent - 从产品文档抽取业务流程、角色、对象、规则和状态流转
 *
 * 职责：
 * - 抽取业务流程
 * - 识别参与角色
 * - 提取业务对象
 * - 解析业务规则
 * - 识别状态流转
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocUnderstandingAgent {

    private final LlmGateway llmGateway;

    /**
     * 业务事实抽取结果 - 对应 BusinessProcessExtractionResult Schema
     */
    @Data
    public static class BusinessFactExtraction {
        private List<BusinessDomain> businessDomains = new ArrayList<>();
        private List<BusinessProcess> businessProcesses = new ArrayList<>();
        private List<BusinessObject> businessObjects = new ArrayList<>();
        private List<BusinessRule> businessRules = new ArrayList<>();
        private List<String> roles = new ArrayList<>();
        private List<StatusTransition> statusTransitions = new ArrayList<>();
        private List<String> features = new ArrayList<>();
        private List<Evidence> evidence = new ArrayList<>();
    }

    @Data
    public static class BusinessDomain {
        private String name;
        private String description;
        private double confidence;
        private String evidenceText;
    }

    @Data
    public static class BusinessProcess {
        private String key;
        private String name;
        private String description;
        private List<String> steps;
        private List<String> roles;
        private List<String> objects;
        private List<String> rules;
        private double confidence;
        private List<EvidenceRef> evidence;
    }

    @Data
    public static class BusinessObject {
        private String name;
        private String description;
        private List<String> attributes;
        private double confidence;
        private List<EvidenceRef> evidence;
    }

    @Data
    public static class BusinessRule {
        private String name;
        private String expression;
        private double confidence;
        private List<EvidenceRef> evidence;
    }

    @Data
    public static class StatusTransition {
        private String businessObject;
        private String fromStatus;
        private String toStatus;
        private String trigger;
        private double confidence;
        private List<EvidenceRef> evidence;
    }

    @Data
    public static class Evidence {
        private String chunkTitle;
        private String contentExcerpt;
        private int chunkIndex;
    }

    @Data
    public static class EvidenceRef {
        private String sourceType;
        private String sourceUri;
        private Integer lineStart;
        private Integer lineEnd;
        private String excerpt;
    }

    /**
     * 从文档片段抽取业务事实
     */
    public BusinessFactExtraction extractBusinessFacts(String projectId, String docContent, String sourcePath) {
        Map<String, String> variables = new HashMap<>();
        variables.put("docContent", docContent);
        variables.put("sourcePath", sourcePath);

        return llmGateway.callWithTemplate(projectId, "doc-understanding",
                variables, BusinessFactExtraction.class);
    }

    /**
     * 将文档理解结果转换为证据化 Claim 草稿。
     * <p>Agent 只输出结构化候选知识，持久化和状态计算由 KnowledgeClaimService 负责。</p>
     */
    public List<KnowledgeClaimDraft> toClaimDrafts(String projectId, String versionId,
                                                   BusinessFactExtraction extraction,
                                                   String sourcePath) {
        List<KnowledgeClaimDraft> drafts = new ArrayList<>();
        if (extraction == null) {
            return drafts;
        }
        if (extraction.getBusinessDomains() != null) {
            for (BusinessDomain domain : extraction.getBusinessDomains()) {
                if (domain == null || !hasText(domain.getName())) {
                    continue;
                }
                drafts.add(draft(projectId, versionId, "BusinessDomain", "domain:" + domain.getName(),
                        "MENTIONED_IN", sourcePath, domain.getConfidence()));
            }
        }
        if (extraction.getBusinessProcesses() != null) {
            for (BusinessProcess process : extraction.getBusinessProcesses()) {
                if (process == null || !hasText(process.getName())) {
                    continue;
                }
                String key = hasText(process.getKey()) ? process.getKey() : "process:" + process.getName();
                drafts.add(draft(projectId, versionId, "BusinessProcess", key,
                        "MENTIONED_IN", sourcePath, process.getConfidence()));
            }
        }
        if (extraction.getFeatures() != null) {
            for (String feature : extraction.getFeatures()) {
                if (!hasText(feature)) {
                    continue;
                }
                drafts.add(draft(projectId, versionId, "Feature", "feature:" + feature,
                        "DESCRIBED_BY", sourcePath, 0.7));
            }
        }
        if (extraction.getBusinessObjects() != null) {
            for (BusinessObject object : extraction.getBusinessObjects()) {
                if (object == null || !hasText(object.getName())) {
                    continue;
                }
                drafts.add(draft(projectId, versionId, "BusinessObject", "object:" + object.getName(),
                        "MENTIONED_IN", sourcePath, object.getConfidence()));
            }
        }
        if (extraction.getBusinessRules() != null) {
            for (BusinessRule rule : extraction.getBusinessRules()) {
                if (rule == null || !hasText(rule.getName())) {
                    continue;
                }
                drafts.add(draft(projectId, versionId, "BusinessRule", "rule:" + rule.getName(),
                        "MENTIONED_IN", sourcePath, rule.getConfidence()));
            }
        }
        if (extraction.getStatusTransitions() != null) {
            for (StatusTransition transition : extraction.getStatusTransitions()) {
                if (transition == null || !hasText(transition.getBusinessObject())) {
                    continue;
                }
                String key = "transition:" + transition.getBusinessObject()
                        + ":" + valueOrUnknown(transition.getFromStatus())
                        + "->" + valueOrUnknown(transition.getToStatus())
                        + ":" + valueOrUnknown(transition.getTrigger());
                drafts.add(draft(projectId, versionId, "StateTransition", key,
                        "MENTIONED_IN", sourcePath, transition.getConfidence()));
            }
        }
        if (extraction.getRoles() != null) {
            for (String role : extraction.getRoles()) {
                if (!hasText(role)) {
                    continue;
                }
                drafts.add(draft(projectId, versionId, "Role", "role:" + role,
                        "MENTIONED_IN", sourcePath, 0.7));
            }
        }
        return drafts;
    }

    /**
     * 计算抽取结果的平均置信度
     */
    public double calculateAverageConfidence(BusinessFactExtraction result) {
        double total = 0;
        int count = 0;

        for (BusinessDomain d : result.getBusinessDomains()) {
            total += d.getConfidence();
            count++;
        }
        for (BusinessProcess p : result.getBusinessProcesses()) {
            total += p.getConfidence();
            count++;
        }
        for (BusinessObject o : result.getBusinessObjects()) {
            total += o.getConfidence();
            count++;
        }
        for (BusinessRule r : result.getBusinessRules()) {
            total += r.getConfidence();
            count++;
        }
        for (StatusTransition t : result.getStatusTransitions()) {
            total += t.getConfidence();
            count++;
        }

        if (count == 0) return 0;
        return total / count;
    }

    private KnowledgeClaimDraft draft(String projectId, String versionId, String subjectType,
                                      String subjectKey, String predicate, String sourcePath,
                                      double confidence) {
        return KnowledgeClaimDraft.builder()
                .projectId(projectId)
                .versionId(versionId)
                .subjectType(subjectType)
                .subjectKey(subjectKey)
                .predicate(predicate)
                .objectType("Evidence")
                .objectKey(sourcePath)
                .sourceType("DOC_AI")
                .extractor("DocUnderstandingAgent")
                .confidence(BigDecimal.valueOf(confidence > 0 ? confidence : 0.7))
                .evidenceIds(hasText(sourcePath) ? List.of(sourcePath) : List.of())
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String valueOrUnknown(String value) {
        return hasText(value) ? value : "?";
    }
}
