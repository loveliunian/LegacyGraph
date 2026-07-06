package io.github.legacygraph.governance;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphifyAccessPolicy 单元测试。
 */
class GraphifyAccessPolicyTest {

    private final GraphifyAccessPolicy policy = new GraphifyAccessPolicy();

    @Test
    void adminCanRunImport() {
        assertTrue(policy.canRunImport(Set.of(Role.GRAPHIFY_ADMIN)));
    }

    @Test
    void reviewerCannotRunImport() {
        assertFalse(policy.canRunImport(Set.of(Role.GRAPH_REVIEWER)));
    }

    @Test
    void viewerCannotRunImport() {
        assertFalse(policy.canRunImport(Set.of(Role.GRAPH_VIEWER)));
    }

    @Test
    void evidenceViewerCannotRunImport() {
        assertFalse(policy.canRunImport(Set.of(Role.GRAPH_EVIDENCE_VIEWER)));
    }

    @Test
    void adminCanRetryJob() {
        assertTrue(policy.canRetryJob(Set.of(Role.GRAPHIFY_ADMIN)));
    }

    @Test
    void reviewerCannotRetryJob() {
        assertFalse(policy.canRetryJob(Set.of(Role.GRAPH_REVIEWER)));
    }

    @Test
    void adminCanRollbackJob() {
        assertTrue(policy.canRollbackJob(Set.of(Role.GRAPHIFY_ADMIN)));
    }

    @Test
    void reviewerCannotRollbackJob() {
        assertFalse(policy.canRollbackJob(Set.of(Role.GRAPH_REVIEWER)));
    }

    @Test
    void adminCanModifyConfig() {
        assertTrue(policy.canModifyConfig(Set.of(Role.GRAPHIFY_ADMIN)));
    }

    @Test
    void reviewerCannotModifyConfig() {
        assertFalse(policy.canModifyConfig(Set.of(Role.GRAPH_REVIEWER)));
    }

    @Test
    void adminCanReviewCandidate() {
        assertTrue(policy.canReviewCandidate(Set.of(Role.GRAPHIFY_ADMIN)));
    }

    @Test
    void reviewerCanReviewCandidate() {
        assertTrue(policy.canReviewCandidate(Set.of(Role.GRAPH_REVIEWER)));
    }

    @Test
    void viewerCannotReviewCandidate() {
        assertFalse(policy.canReviewCandidate(Set.of(Role.GRAPH_VIEWER)));
    }

    @Test
    void adminCanViewRawEvidence() {
        assertTrue(policy.canViewRawEvidence(Set.of(Role.GRAPHIFY_ADMIN)));
    }

    @Test
    void evidenceViewerCanViewRawEvidence() {
        assertTrue(policy.canViewRawEvidence(Set.of(Role.GRAPH_EVIDENCE_VIEWER)));
    }

    @Test
    void reviewerCannotViewRawEvidence() {
        assertFalse(policy.canViewRawEvidence(Set.of(Role.GRAPH_REVIEWER)));
    }

    @Test
    void viewerCannotViewRawEvidence() {
        assertFalse(policy.canViewRawEvidence(Set.of(Role.GRAPH_VIEWER)));
    }

    @Test
    void adminCanViewGraph() {
        assertTrue(policy.canViewGraph(Set.of(Role.GRAPHIFY_ADMIN)));
    }

    @Test
    void reviewerCanViewGraph() {
        assertTrue(policy.canViewGraph(Set.of(Role.GRAPH_REVIEWER)));
    }

    @Test
    void evidenceViewerCanViewGraph() {
        assertTrue(policy.canViewGraph(Set.of(Role.GRAPH_EVIDENCE_VIEWER)));
    }

    @Test
    void viewerCanViewGraph() {
        assertTrue(policy.canViewGraph(Set.of(Role.GRAPH_VIEWER)));
    }

    @Test
    void emptyRolesCannotDoAnything() {
        Set<Role> empty = Set.of();
        assertFalse(policy.canRunImport(empty));
        assertFalse(policy.canRetryJob(empty));
        assertFalse(policy.canRollbackJob(empty));
        assertFalse(policy.canModifyConfig(empty));
        assertFalse(policy.canReviewCandidate(empty));
        assertFalse(policy.canViewRawEvidence(empty));
        assertFalse(policy.canViewGraph(empty));
    }

    @Test
    void multipleRolesGrantHighestPermission() {
        Set<Role> roles = Set.of(Role.GRAPH_VIEWER, Role.GRAPH_REVIEWER);
        assertTrue(policy.canReviewCandidate(roles));
        assertFalse(policy.canRunImport(roles));
        assertFalse(policy.canViewRawEvidence(roles));
    }
}
