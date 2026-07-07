package io.github.legacygraph.service.test;

import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.PatchPlan;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PatchPlan 落盘前校验器（增强版2）— 见 doc §PatchPlan 输出契约 的三类校验。
 * <ol>
 *   <li>范围校验：patch 文件必须属于 impacted subgraph 覆盖到的文件，越界改为 REVIEW_PENDING。</li>
 *   <li>格式校验：patch 必须是 unified diff（含 ---/+++/@@ 标记）。</li>
 *   <li>证据校验：每个 patch 至少引用一个 Evidence。</li>
 * </ol>
 */
@Slf4j
@Service
public class PatchPlanValidator {

    /**
     * 校验 PatchPlan。任一 patch 越界或无证据 → needsReview=true（调用方应转 REVIEW_PENDING）。
     */
    public ValidationResult validate(PatchPlan plan, ImpactSubgraph subgraph) {
        ValidationResult result = new ValidationResult();
        if (plan == null || plan.getPatches() == null || plan.getPatches().isEmpty()) {
            result.setValid(false);
            result.getErrors().add("PatchPlan 无补丁内容");
            return result;
        }

        Set<String> allowedFiles = subgraph != null && subgraph.getImpactedFiles() != null
                ? subgraph.getImpactedFiles().stream().collect(Collectors.toSet())
                : Set.of();

        for (PatchPlan.Patch patch : plan.getPatches()) {
            String fp = patch.getFilePath();

            // ① 范围校验
            if (!allowedFiles.isEmpty() && fp != null && !allowedFiles.contains(fp)) {
                result.getOutOfScopeFiles().add(fp);
                result.setNeedsReview(true);
            }

            // ② 格式校验（DELETE 允许空 diff；.sql Flyway 脚本是 SQL 文本，非 unified diff，由 ④ 校验）
            boolean isSqlScript = fp != null && fp.endsWith(".sql");
            if (!"DELETE".equalsIgnoreCase(patch.getChangeType())
                    && !isSqlScript
                    && !isUnifiedDiff(patch.getPatchText())) {
                result.getErrors().add("补丁非 unified diff 格式: " + fp);
                result.setValid(false);
            }

            // ③ 证据校验
            if (patch.getEvidenceIds() == null || patch.getEvidenceIds().isEmpty()) {
                result.getMissingEvidenceFiles().add(fp);
                result.setNeedsReview(true);
            }

            // ④ DDL 专项校验（ADD_COLUMN）：.sql 文件须为 ALTER TABLE ADD COLUMN；否则 needsReview
            if (fp != null && fp.endsWith(".sql")) {
                String patchText = patch.getPatchText() != null ? patch.getPatchText().toUpperCase() : "";
                if (!patchText.contains("ALTER TABLE") || !patchText.contains("ADD COLUMN")) {
                    result.getDdlViolations().add("非有效 ADD COLUMN DDL: " + fp);
                    result.setNeedsReview(true);
                }
            }
        }

        if (!result.getErrors().isEmpty()) {
            result.setValid(false);
        }
        log.info("PatchPlan validate: valid={}, needsReview={}, outOfScope={}, missingEvidence={}, ddlViolations={}",
                result.isValid(), result.isNeedsReview(),
                result.getOutOfScopeFiles().size(), result.getMissingEvidenceFiles().size(),
                result.getDdlViolations().size());
        return result;
    }

    /** 判断文本是否为 unified diff（宽松校验：包含 diff 头或 hunk 标记）。 */
    private boolean isUnifiedDiff(String text) {
        if (text == null || text.isBlank()) return false;
        boolean hasHunk = text.contains("@@");
        boolean hasFileHeader = text.contains("--- ") && text.contains("+++ ");
        boolean hasGitHeader = text.contains("diff --git");
        return hasHunk || hasFileHeader || hasGitHeader;
    }

    @Data
    public static class ValidationResult {
        private boolean valid = true;
        /** 存在越界/缺证据时为 true，调用方应把任务/补丁转 REVIEW_PENDING */
        private boolean needsReview = false;
        private List<String> errors = new ArrayList<>();
        private List<String> outOfScopeFiles = new ArrayList<>();
        private List<String> missingEvidenceFiles = new ArrayList<>();
        /** DDL 专项校验违规（ADD_COLUMN 场景） */
        private List<String> ddlViolations = new ArrayList<>();
    }
}
