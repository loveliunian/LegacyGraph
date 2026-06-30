package io.github.legacygraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 报告洞察与行动建议结果 — 对应 report-insight 模板输出。
 */
@Data
public class ReportInsight {

    private String summary;
    private List<ActionItem> actions = new ArrayList<>();

    @Data
    public static class ActionItem {
        private String title;
        private String actionType;
        private String priority;
        private String source;
        private List<String> targets = new ArrayList<>();
        private String rationale;
        private String expectedBenefit;
    }
}
