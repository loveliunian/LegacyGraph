package io.github.legacygraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 性能分析结果 — 对应 sql-advisor 模板输出。
 */
@Data
public class SqlAdvisorResult {

    private String sqlKey;
    private List<SqlIssue> issues = new ArrayList<>();
    private String optimizedSql;
    private String overallRisk;
    private String summary;

    @Data
    public static class SqlIssue {
        private String issueType;
        private String severity;
        private String description;
        private String suggestion;
    }
}
