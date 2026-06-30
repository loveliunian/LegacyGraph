package io.github.legacygraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码异味重构建议结果 — 对应 refactor-suggestion 模板输出。
 */
@Data
public class RefactorSuggestion {

    private String summary;
    private List<String> responsibilities = new ArrayList<>();
    private List<SplitSuggestion> splitSuggestions = new ArrayList<>();
    private String refactoredSkeleton;
    private List<String> impacts = new ArrayList<>();
    private String risk;

    @Data
    public static class SplitSuggestion {
        private String newUnit;
        private String responsibility;
        private List<String> movedMethods = new ArrayList<>();
    }
}
