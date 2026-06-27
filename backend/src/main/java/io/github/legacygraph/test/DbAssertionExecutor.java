package io.github.legacygraph.test;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据库断言执行器
 * 执行数据库查询断言，验证数据是否存在、符合预期
 */
@Slf4j
@Component
public class DbAssertionExecutor {

    private final DataSource dataSource;

    public DbAssertionExecutor(DataSource dataSource) {
        this.dataSource = dataSource;
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
