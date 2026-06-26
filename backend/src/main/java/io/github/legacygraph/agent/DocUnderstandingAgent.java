package io.github.legacygraph.agent;

import io.github.legacygraph.llm.LlmGateway;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
public class DocUnderstandingAgent {

    @Autowired
    private LlmGateway llmGateway;

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
}
