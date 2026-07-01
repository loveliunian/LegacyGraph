package io.github.legacygraph.agent;

import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.dto.TestCaseGenerationResult;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.llm.LlmGateway;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class TestCaseAgent {

    private final LlmGateway llmGateway;

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

        // 模板单次调用返回 testCases 数组，覆盖正常/异常/边界多个场景
        List<GeneratedTestCase> results = new ArrayList<>();

        TestCaseGenerationResult generation = llmGateway.callWithTemplate(request.getProjectId(),
                "test-case-generation", variables, TestCaseGenerationResult.class);
        if (generation != null && generation.getTestCases() != null) {
            for (GeneratedTestCase tc : generation.getTestCases()) {
                if (tc == null) {
                    continue;
                }
                // 补全 featureKey，避免模板遗漏
                if (tc.getFeatureKey() == null || tc.getFeatureKey().isEmpty()) {
                    tc.setFeatureKey(request.getFeatureKey());
                }
                results.add(tc);
            }
        }

        log.info("Generated {} test cases for feature {}", results.size(), request.getFeatureKey());
        return results;
    }
}
