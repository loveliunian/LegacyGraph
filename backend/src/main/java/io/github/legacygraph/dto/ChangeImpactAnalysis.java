package io.github.legacygraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 变更影响分析结果 — 对应 change-impact 模板输出。
 */
@Data
public class ChangeImpactAnalysis {

    private String changeType;
    private String severity;
    private String summary;
    private List<String> impactedNodes = new ArrayList<>();
    private List<String> affectedTests = new ArrayList<>();
    private List<String> regressionScope = new ArrayList<>();
}
