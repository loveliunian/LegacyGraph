package io.github.legacygraph.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.TestAssertion;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.TestAssertionRepository;
import io.github.legacygraph.repository.TestResultRepository;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;

/**
 * API测试执行器
 * 使用 REST Assured 执行生成的API测试用例并收集结果
 */
@Slf4j
@Component
public class ApiTestExecutor {

    private final TestResultRepository testResultRepository;
    private final TestAssertionRepository testAssertionRepository;
    private final DbAssertionExecutor dbAssertionExecutor;
    private final ObjectMapper objectMapper;

    public ApiTestExecutor(TestResultRepository testResultRepository,
                          TestAssertionRepository testAssertionRepository,
                          DbAssertionExecutor dbAssertionExecutor,
                          ObjectMapper objectMapper) {
        this.testResultRepository = testResultRepository;
        this.testAssertionRepository = testAssertionRepository;
        this.dbAssertionExecutor = dbAssertionExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行单个API测试用例
     */
    @Transactional
    public TestResult execute(TestCase testCase, String baseUrl, String executionId) {
        log.info("Executing API test case: {} {}", testCase.getCaseCode(), testCase.getCaseName());

        TestResult result = new TestResult();
        result.setId(UUID.randomUUID().toString());
        result.setProjectId(testCase.getProjectId());
        result.setVersionId(testCase.getVersionId());
        result.setTestCaseId(testCase.getId());
        result.setExecutionId(executionId);
        result.setExecutedAt(LocalDateTime.now());

        long startTime = System.currentTimeMillis();

        try {
            // 解析测试步骤
            List<Map<String, Object>> steps = parseJson(testCase.getSteps(), List.class);
            Map<String, Object> expected = parseJson(testCase.getExpectedResult(), Map.class);
            List<Map<String, Object>> preconditions = parseJson(testCase.getPreconditions(), List.class);

            // 处理前置条件（获取登录token等）
            Map<String, String> context = processPreconditions(preconditions, baseUrl);

            // 找到API调用步骤
            Map<String, Object> apiStep = findApiStep(steps);
            if (apiStep == null) {
                throw new IllegalArgumentException("No API call step found in test case");
            }

            String method = (String) apiStep.get("method");
            String path = (String) apiStep.get("path");
            Map<String, Object> body = (Map<String, Object>) apiStep.get("body");
            Map<String, String> headers = (Map<String, String>) apiStep.get("headers");

            // 替换模板变量
            if (headers != null) {
                headers = replaceTemplate(headers, context);
            }
            if (body != null) {
                body = replaceTemplate(body, context);
            }

            // 执行请求
            String fullUrl = baseUrl + path;
            Response response = executeRequest(method, fullUrl, headers, body);

            // 保存请求响应
            result.setRequestData(objectMapper.writeValueAsString(apiStep));
            result.setResponseData(objectMapper.writeValueAsString(response.asString()));
            result.setDurationMs(System.currentTimeMillis() - startTime);

            // 执行断言
            List<Map<String, Object>> assertions = expected != null ?
                    (List<Map<String, Object>>) expected.get("assertions") : null;

            boolean allPassed = executeAssertions(testCase.getId(), response, assertions);
            result.setResultStatus(allPassed ? "PASSED" : "FAILED");

            log.info("Test case {} completed: {}, duration: {}ms",
                    testCase.getCaseCode(), result.getResultStatus(), result.getDurationMs());

        } catch (Exception e) {
            log.error("Test case {} failed with exception", testCase.getCaseCode(), e);
            result.setResultStatus("ERROR");
            result.setErrorMessage(e.getMessage());
            result.setDurationMs(System.currentTimeMillis() - startTime);
        }

        testResultRepository.save(result);
        return result;
    }

    /**
     * 处理前置条件
     */
    private Map<String, String> processPreconditions(List<Map<String, Object>> preconditions, String baseUrl) {
        Map<String, String> context = new HashMap<>();

        if (preconditions == null) return context;

        for (var pre : preconditions) {
            String type = (String) pre.get("type");
            if ("LOGIN".equals(type)) {
                // 执行登录获取token
                String role = (String) pre.get("role");
                // TODO: 根据配置的登录接口和账号获取token
                // context.put("token", token);
            }
        }

        return context;
    }

    /**
     * 替换模板变量 ${var}
     */
    @SuppressWarnings("unchecked")
    private <T> Map<String, T> replaceTemplate(Map<String, T> input, Map<String, String> context) {
        Map<String, T> result = new HashMap<>();
        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");

        for (Map.Entry<String, T> entry : input.entrySet()) {
            T value = entry.getValue();
            if (value instanceof String) {
                String str = (String) value;
                Matcher matcher = pattern.matcher(str);
                StringBuffer sb = new StringBuffer();
                while (matcher.find()) {
                    String varName = matcher.group(1);
                    if (context.containsKey(varName)) {
                        matcher.appendReplacement(sb, context.get(varName));
                    }
                }
                matcher.appendTail(sb);
                result.put(entry.getKey(), (T) sb.toString());
            } else if (value instanceof Map) {
                result.put(entry.getKey(), (T) replaceTemplate((Map<String, Object>) value, context));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /**
     * 找到API调用步骤
     */
    private Map<String, Object> findApiStep(List<Map<String, Object>> steps) {
        if (steps == null) return null;
        for (var step : steps) {
            String action = (String) step.get("action");
            if ("CALL_API".equals(action)) {
                return step;
            }
        }
        return steps.isEmpty() ? steps.get(0) : null;
    }

    /**
     * 执行HTTP请求
     */
    private Response executeRequest(String method, String url, Map<String, String> headers, Map<String, Object> body) {
        RestAssured.config = RestAssured.config().jsonConfig(
                RestAssured.config().getJsonConfig().numberReturnType(io.restassured.common.config.JsonConfig.NumberReturnType.BIG_DECIMAL)
        );

        var request = given();
        if (headers != null) {
            request.headers(headers);
        }
        if (body != null) {
            request.contentType("application/json").body(body);
        }

        switch (method.toUpperCase()) {
            case "GET":
                return request.get(url);
            case "POST":
                return request.post(url);
            case "PUT":
                return request.put(url);
            case "DELETE":
                return request.delete(url);
            case "PATCH":
                return request.patch(url);
            default:
                return request.get(url);
        }
    }

    /**
     * 执行断言
     */
    private boolean executeAssertions(String testCaseId, Response response, List<Map<String, Object>> assertions) {
        boolean allPassed = true;

        if (assertions == null) return allPassed;

        for (var assertionDef : assertions) {
            TestAssertion assertion = new TestAssertion();
            assertion.setId(UUID.randomUUID().toString());
            assertion.setTestCaseId(testCaseId);
            assertion.setAssertionType((String) assertionDef.get("type"));
            assertion.setAssertionName((String) assertionDef.get("type") + " assertion");
            assertion.setExpression((String) assertionDef.get("expression"));
            assertion.setExpectedValue(toJson(assertionDef.get("expected")));
            assertion.setCreatedAt(LocalDateTime.now());
            assertion.setUpdatedAt(LocalDateTime.now());

            boolean passed = evaluateAssertion(assertion, response, assertionDef);
            assertion.setStatus(passed ? "PASSED" : "FAILED");
            testAssertionRepository.save(assertion);

            if (!passed) {
                allPassed = false;
            }
        }

        return allPassed;
    }

    /**
     * 评估单个断言
     */
    private boolean evaluateAssertion(TestAssertion assertion, Response response, Map<String, Object> assertionDef) {
        String type = assertion.getAssertionType();

        switch (type) {
            case "HTTP_STATUS":
                int expected = ((Number) assertionDef.get("expected")).intValue();
                return response.getStatusCode() == expected;

            case "JSON_PATH":
                String path = (String) assertionDef.get("expression");
                Object expected = assertionDef.get("expected");
                Object actual = response.jsonPath().get(path);
                return objectsEqual(expected, actual);

            case "JSON_PATH_NOT_NULL":
                String pathNN = (String) assertionDef.get("expression");
                Object actualNN = response.jsonPath().get(pathNN);
                return actualNN != null;

            case "DB_EXISTS":
                // 数据库断言 - 执行查询验证数据存在
                String sql = (String) assertionDef.get("expression");
                DbAssertionExecutor.AssertionResult result = dbAssertionExecutor.executeExists(sql);
                return result.isPassed();

            case "DB_NOT_EXISTS":
                // 数据库断言 - 验证数据不存在
                String sqlNe = (String) assertionDef.get("expression");
                DbAssertionExecutor.AssertionResult resultNe = dbAssertionExecutor.executeNotExists(sqlNe);
                return resultNe.isPassed();

            case "DB_COUNT":
                // 数据库断言 - 验证行数
                String sqlCount = (String) assertionDef.get("expression");
                long expectedCount = ((Number) assertionDef.get("expected")).longValue();
                double tolerance = assertionDef.get("tolerance") != null ?
                        ((Number) assertionDef.get("tolerance")).doubleValue() : 0.0;
                DbAssertionExecutor.AssertionResult resultCount = dbAssertionExecutor.executeCount(sqlCount, expectedCount, tolerance);
                return resultCount.isPassed();

            case "DB_FIELD_VALUE":
                // 数据库断言 - 验证字段值
                String sqlField = (String) assertionDef.get("expression");
                String columnName = (String) assertionDef.get("column");
                Object expectedValue = assertionDef.get("expected");
                DbAssertionExecutor.AssertionResult resultField = dbAssertionExecutor.executeFieldValue(sqlField, columnName, expectedValue);
                return resultField.isPassed();

            default:
                log.warn("Unknown assertion type: {}", type);
                return true;
        }
    }

    /**
     * 比较对象是否相等（容忍类型差异）
     */
    private boolean objectsEqual(Object expected, Object actual) {
        if (expected == null && actual == null) return true;
        if (expected == null || actual == null) return false;

        if (expected instanceof Number && actual instanceof Number) {
            return ((Number) expected).doubleValue() == ((Number) actual).doubleValue();
        }

        return expected.equals(actual);
    }

    private <T> T parseJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
