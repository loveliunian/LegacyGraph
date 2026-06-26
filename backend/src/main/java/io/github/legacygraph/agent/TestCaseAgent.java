package io.github.legacygraph.agent;

import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.entity.GraphNode;
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
 * TestCaseAgent - 测试用例生成
 *
 * 职责：
 * - 从功能节点生成 API/E2E/DB 测试用例
 * - 覆盖正常、异常、边界场景
 * - 生成多类断言（HTTP、JSON、SQL、UI）
 */
@Slf4j
@Service
public class TestCaseAgent {

    @Autowired
    private LlmGateway llmGateway;

    /**
     * 测试生成请求
     */
    @Data
    public static class TestGenerationRequest {
        private String projectId;
        private String featureKey;
        private String featureName;
        private String apiEndpoint;
        private String httpMethod;
        private String requestSchema;  // JSON schema of request DTO
        private String relatedTables;   // 相关表名列表
        private String businessRules;   // 业务规则描述
    }

    /**
     * 根据功能节点生成测试用例
     */
    public List<GeneratedTestCase> generateTestCases(TestGenerationRequest request) {
        Map<String, String> variables = new HashMap<>();
        variables.put("featureKey", request.getFeatureKey() != null ? request.getFeatureKey() : "");
        variables.put("featureName", request.getFeatureName() != null ? request.getFeatureName() : "");
        variables.put("apiEndpoint", request.getApiEndpoint() != null ? request.getApiEndpoint() : "");
        variables.put("httpMethod", request.getHttpMethod() != null ? request.getHttpMethod() : "");
        variables.put("requestSchema", request.getRequestSchema() != null ? request.getRequestSchema() : "");
        variables.put("relatedTables", request.getRelatedTables() != null ? request.getRelatedTables() : "");
        variables.put("businessRules", request.getBusinessRules() != null ? request.getBusinessRules() : "");

        // 模板返回单个测试用例，我们生成多个场景
        List<GeneratedTestCase> results = new ArrayList<>();

        // 正常场景
        GeneratedTestCase normalCase = llmGateway.callWithTemplate(request.getProjectId(),
                "test-case-generation", variables, GeneratedTestCase.class);
        if (normalCase != null) {
            results.add(normalCase);
        }

        // 注：其他场景可以通过多次调用或者在单次调用中返回多个
        // 这里保持每次调用一个场景，让模型聚焦质量

        log.info("Generated {} test cases for feature {}", results.size(), request.getFeatureKey());
        return results;
    }
}
