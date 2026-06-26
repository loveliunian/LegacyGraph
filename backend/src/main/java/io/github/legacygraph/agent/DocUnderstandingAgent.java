package io.github.legacygraph.agent;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档理解Agent
 * 从文档片段抽取业务事实: 业务域、业务流程、业务对象、业务规则、角色、状态流转
 */
@Slf4j
public class DocUnderstandingAgent {

    /**
     * 业务事实抽取结果
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
        private String name;
        private String description;
        private List<String> steps;
        private double confidence;
        private String evidenceText;
    }

    @Data
    public static class BusinessObject {
        private String name;
        private String description;
        private List<String> attributes;
        private double confidence;
        private String evidenceText;
    }

    @Data
    public static class BusinessRule {
        private String name;
        private String expression;
        private double confidence;
        private String evidenceText;
    }

    @Data
    public static class StatusTransition {
        private String businessObject;
        private String fromStatus;
        private String toStatus;
        private String trigger;
        private double confidence;
        private String evidenceText;
    }

    @Data
    public static class Evidence {
        private String chunkTitle;
        private String contentExcerpt;
        private int chunkIndex;
    }

    /**
     * 获取Prompt模板，用于LLM抽取
     */
    public String getPrompt(String chunkContent) {
        return """
                你是一个老系统业务分析专家。
                请从以下文档片段中抽取业务事实。

                要求：
                1. 只能基于给定内容抽取，不要编造。
                2. 每个事实必须给出证据原文。
                3. 如果不确定，confidence 不要超过 0.6。
                4. 输出 JSON。

                需要抽取：
                - businessDomains: 业务域列表，每个包含 name, description, confidence, evidenceText
                - businessProcesses: 业务流程列表，每个包含 name, description, steps[], confidence, evidenceText
                - businessObjects: 业务对象列表，每个包含 name, description, attributes[], confidence, evidenceText
                - businessRules: 业务规则列表，每个包含 name, expression, confidence, evidenceText
                - roles: 业务角色列表
                - statusTransitions: 状态流转，每个包含 businessObject, fromStatus, toStatus, trigger, confidence, evidenceText
                - features: 功能点列表

                文档片段：
                %s

                输出格式（严格JSON）：
                {
                  "businessDomains": [],
                  "businessProcesses": [],
                  "businessObjects": [],
                  "businessRules": [],
                  "roles": [],
                  "statusTransitions": [],
                  "features": [],
                  "evidence": []
                }
                """.formatted(chunkContent);
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
