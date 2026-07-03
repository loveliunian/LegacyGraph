package io.github.legacygraph.service;

import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.PatchPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import io.github.legacygraph.service.test.PatchPlanValidator;

class PatchPlanValidatorTest {

    private PatchPlanValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PatchPlanValidator();
    }

    private ImpactSubgraph subgraphWithFiles(String... files) {
        return ImpactSubgraph.builder()
                .targetNodeId("n1")
                .impactedFiles(List.of(files))
                .build();
    }

    @Test
    void validPatch_inScope_withEvidence_withDiff() {
        PatchPlan.Patch patch = PatchPlan.Patch.builder()
                .filePath("src/A.java")
                .changeType("MODIFY")
                .patchText("--- a/src/A.java\n+++ b/src/A.java\n@@ -1 +1 @@\n-old\n+new\n")
                .evidenceIds(List.of("evd-1"))
                .build();
        PatchPlan plan = PatchPlan.builder().patches(List.of(patch)).build();

        PatchPlanValidator.ValidationResult r = validator.validate(plan, subgraphWithFiles("src/A.java"));

        assertTrue(r.isValid());
        assertFalse(r.isNeedsReview());
    }

    @Test
    void outOfScopeFile_marksNeedsReview() {
        PatchPlan.Patch patch = PatchPlan.Patch.builder()
                .filePath("src/Evil.java")
                .changeType("MODIFY")
                .patchText("@@ -1 +1 @@")
                .evidenceIds(List.of("evd-1"))
                .build();
        PatchPlan plan = PatchPlan.builder().patches(List.of(patch)).build();

        PatchPlanValidator.ValidationResult r = validator.validate(plan, subgraphWithFiles("src/A.java"));

        assertTrue(r.isNeedsReview());
        assertTrue(r.getOutOfScopeFiles().contains("src/Evil.java"));
    }

    @Test
    void missingEvidence_marksNeedsReview() {
        PatchPlan.Patch patch = PatchPlan.Patch.builder()
                .filePath("src/A.java")
                .changeType("MODIFY")
                .patchText("@@ -1 +1 @@")
                .evidenceIds(List.of())
                .build();
        PatchPlan plan = PatchPlan.builder().patches(List.of(patch)).build();

        PatchPlanValidator.ValidationResult r = validator.validate(plan, subgraphWithFiles("src/A.java"));

        assertTrue(r.isNeedsReview());
        assertTrue(r.getMissingEvidenceFiles().contains("src/A.java"));
    }

    @Test
    void nonUnifiedDiff_isInvalid() {
        PatchPlan.Patch patch = PatchPlan.Patch.builder()
                .filePath("src/A.java")
                .changeType("MODIFY")
                .patchText("just some prose, not a diff")
                .evidenceIds(List.of("evd-1"))
                .build();
        PatchPlan plan = PatchPlan.builder().patches(List.of(patch)).build();

        PatchPlanValidator.ValidationResult r = validator.validate(plan, subgraphWithFiles("src/A.java"));

        assertFalse(r.isValid());
    }

    @Test
    void emptyPlan_isInvalid() {
        PatchPlan plan = PatchPlan.builder().patches(List.of()).build();
        PatchPlanValidator.ValidationResult r = validator.validate(plan, subgraphWithFiles("src/A.java"));
        assertFalse(r.isValid());
    }
}
