package io.github.legacygraph.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 补丁计划契约（增强版2）— 见 doc §PatchPlan 输出契约。
 * <p>
 * 补丁 Agent/Adapter 不返回自然语言，而返回可校验的 PatchPlan。落盘前必须过：
 * ① 范围校验（patch 文件属于 impacted subgraph）；② 格式校验（unified diff）；
 * ③ 证据校验（每个 patch 至少引用一个 Evidence/FeatureSlice/TestFailureAnalysis）。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatchPlan {

    private String taskId;

    /** BUGFIX / REFACTOR / UPGRADE */
    private String taskType;

    /** LOW / MEDIUM / HIGH */
    private String riskLevel;

    private List<ImpactedFile> impactedFiles;

    private List<Patch> patches;

    private List<NewTest> newTests;

    /** 需要执行的验证门禁类型：STATIC / UNIT / API / DB / E2E / MIGRATION */
    private List<String> validationGates;

    private boolean manualReviewNeeded;

    /** 生成来源 Agent（refactor / migration / repair 等） */
    private String generatedBy;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImpactedFile {
        private String path;
        private String reason;

    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Patch {
        private String filePath;
        /** CREATE / MODIFY / DELETE */
        private String changeType;
        private String patchText;
        private List<String> evidenceIds;

    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewTest {
        /** UNIT / API / DB / E2E */
        private String type;
        private String target;
        private String purpose;

    }
}
