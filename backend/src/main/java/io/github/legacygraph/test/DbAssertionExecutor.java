package io.github.legacygraph.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.TestResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据库断言执行器
 * 执行数据库查询断言，验证数据是否存在、符合预期
 */
@Slf4j
@Component
public class DbAssertionExecutor {

    private final DataSource dataSource;
    private final TestResultRepository testResultRepository;
    private final ObjectMapper objectMapper;

    public DbAssertionExecutor(DataSource dataSource,
                              TestResultRepository testResultRepository,
                              ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.testResultRepository = testResultRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行数据库测试用例
     * 解析测试步骤中的断言定义并逐一执行
     */
    @Transactional
    public TestResult execute(TestCase testCase, String executionId) {
        log.info("Executing DB test case: {} {}", testCase.getCaseCode(), testCase.getCaseName());

        TestResult result = new TestResult();
        result.setId(UUID.randomUUID().toString());
        result.setProjectId(testCase.getProjectId());
        result.setVersionId(testCase.getVersionId());
        result.setTestCaseId(testCase.getId());
        result.setExecutionId(executionId);
        result.setExecutedAt(LocalDateTime.now());

        long startTime = System.currentTimeMillis();

        try {
            // 解析测试步骤（多个断言）
            List<Map<String, Object>> assertions = parseJson(testCase.getSteps(), List.class);

            boolean allPassed = true;
            List<AssertionResult> assertionResults = new ArrayList<>();

            if (assertions != null) {
                for (Map<String, Object> assertionDef : assertions) {
                    String type = (String) assertionDef.get("type");
                    AssertionResult assertionResult = executeAssertion(type, assertionDef);
                    assertionResults.add(assertionResult);
                    if (!assertionResult.isPassed()) {
                        allPassed = false;
                    }
                }
            }

            result.setResultStatus(allPassed ? "PASSED" : "FAILED");
            result.setAssertionResult(objectMapper.writeValueAsString(assertionResults));
            result.setDurationMs(System.currentTimeMillis() - startTime);

            log.info("DB test case {} completed: {}, duration: {}ms",
                    testCase.getCaseCode(), result.getResultStatus(), result.getDurationMs());

        } catch (Exception e) {
            log.error("DB test case {} failed with exception", testCase.getCaseCode(), e);
            result.setResultStatus("ERROR");
            result.setErrorMessage(e.getMessage());
            result.setDurationMs(System.currentTimeMillis() - startTime);
        }

        testResultRepository.insert(result);
        return result;
    }

    /**
     * 根据断言类型执行单个断言
     */
    private AssertionResult executeAssertion(String type, Map<String, Object> assertionDef) {
        switch (type) {
            case "ROW_COUNT": {
                String table = (String) assertionDef.get("table");
                String operator = (String) assertionDef.get("operator");
                long expected = ((Number) assertionDef.get("expected")).longValue();

                // 构建 count 查询
                String sql = "SELECT COUNT(*) FROM " + table;

                AssertionResult countResult = executeCount(sql, expected, 0.0);

                // 根据操作符判断是否通过
                if ("GREATER_THAN".equals(operator)) {
                    boolean passed = ((Long) expected) < countResult.getScore();
                    return new AssertionResult(passed,
                            String.format("预期行数 > %d, 实际 %d", expected, (long) countResult.getScore()),
                            passed ? 1.0 : 0.0,
                            null);
                } else if ("EQUAL".equals(operator)) {
                    boolean passed = expected == (long) countResult.getScore();
                    return new AssertionResult(passed,
                            String.format("预期行数 = %d, 实际 %d", expected, (long) countResult.getScore()),
                            passed ? 1.0 : 0.0,
                            null);
                }
                return countResult;
            }
            default:
                return new AssertionResult(false, "Unknown assertion type: " + type, 0.0, null);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T parseJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 执行存在性断言 - 验证至少有一行数据
     */
    public AssertionResult executeExists(String sql) {
        return executeQuery(sql, result -> {
            boolean hasRows = result.next();
            return new AssertionResult(
                hasRows,
                hasRows ? "数据存在" : "数据不存在",
                hasRows ? 1 : 0,
                null
            );
        });
    }

    /**
     * 执行不存在性断言 - 验证没有数据
     */
    public AssertionResult executeNotExists(String sql) {
        AssertionResult result = executeExists(sql);
        return new AssertionResult(
            !result.passed,
            !result.passed ? "数据不存在" : "数据存在，不符合预期",
            !result.passed ? 1 : 0,
            null
        );
    }

    /**
     * 执行计数断言 - 验证行数符合预期
     */
    public AssertionResult executeCount(String sql, long expectedCount, double tolerance) {
        return executeQuery(sql, result -> {
            long actualCount = 0;
            while (result.next()) {
                actualCount++;
            }

            boolean passed = Math.abs(actualCount - expectedCount) <= tolerance * expectedCount;
            String message = String.format("预期行数: %d, 实际: %d", expectedCount, actualCount);
            double score = passed ? 1.0 : 0.0;

            return new AssertionResult(passed, message, score, null);
        });
    }

    /**
     * 执行字段值断言 - 验证指定列符合预期值
     */
    public AssertionResult executeFieldValue(String sql, String columnName, Object expectedValue) {
        return executeQuery(sql, result -> {
            if (!result.next()) {
                return new AssertionResult(false, "没有查询结果", 0.0, null);
            }

            Object actualValue = result.getObject(columnName);
            boolean passed = compareValues(actualValue, expectedValue);
            String message = String.format("列 '%s' 预期: %s, 实际: %s", columnName, expectedValue, actualValue);
            double score = passed ? 1.0 : 0.0;

            return new AssertionResult(passed, message, score, null);
        });
    }

    /**
     * 通用查询执行
     */
    private AssertionResult executeQuery(String sql, QueryHandler handler) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            return handler.handle(rs);
        } catch (Exception e) {
            log.error("Database assertion execution failed: {}", e.getMessage(), e);
            return new AssertionResult(false, "执行异常: " + e.getMessage(), 0.0, e);
        }
    }

    /**
     * 查询并返回所有结果行（用于断言自定义）
     */
    public List<Map<String, Object>> queryForList(String sql) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (rs.next()) {
                // 这里可以用 Jackson 转换成 Map
                // 简化实现，占位
            }
        }
        return result;
    }

    /**
     * 比较值是否相等（容忍类型转换）
     */
    private boolean compareValues(Object actual, Object expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;

        // 都转为字符串比较
        String actualStr = actual.toString().trim();
        String expectedStr = expected.toString().trim();

        // 尝试数值比较
        if (isNumeric(actualStr) && isNumeric(expectedStr)) {
            double actualNum = Double.parseDouble(actualStr);
            double expectedNum = Double.parseDouble(expectedStr);
            return Math.abs(actualNum - expectedNum) < 0.0001;
        }

        return actualStr.equals(expectedStr);
    }

    private boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 断言结果
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class AssertionResult {
        private final boolean passed;
        private final String message;
        private final double score;
        private final Exception exception;
    }

    @FunctionalInterface
    private interface QueryHandler {
        AssertionResult handle(ResultSet rs) throws Exception;
    }
}
