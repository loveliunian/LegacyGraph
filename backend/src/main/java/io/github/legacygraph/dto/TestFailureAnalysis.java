package io.github.legacygraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试失败根因分析结果 — 对应 test-failure-analysis 模板输出。
 */
@Data
public class TestFailureAnalysis {

    private String summary;
    private List<RootCause> rootCauses = new ArrayList<>();
    private List<String> relatedArtifacts = new ArrayList<>();
    private List<String> troubleshootingSteps = new ArrayList<>();
    private Boolean shouldLowerConfidence;
    private List<String> rerunScope = new ArrayList<>();

    @Data
    public static class RootCause {
        private String cause;
        private String likelihood;
        private String evidence;
    }
}
