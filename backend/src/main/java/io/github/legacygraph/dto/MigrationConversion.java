package io.github.legacygraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 迁移代码转换结果 — 对应 migration-convert 模板输出。
 */
@Data
public class MigrationConversion {

    private String summary;
    private List<MigrationChange> changes = new ArrayList<>();
    private String migratedCode;
    private List<String> manualReviewNeeded = new ArrayList<>();

    @Data
    public static class MigrationChange {
        private String ruleType;
        private String before;
        private String after;
        private String reason;
    }
}
