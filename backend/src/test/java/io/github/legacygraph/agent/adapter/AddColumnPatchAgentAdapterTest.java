package io.github.legacygraph.agent.adapter;

import io.github.legacygraph.agent.AddColumnPatchAgent;
import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.PatchPlan;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link AddColumnPatchAgentAdapter} 单元测试 — 验证 LLM 降级与 NOT NULL 无默认值强制人工复核。
 */
@ExtendWith(MockitoExtension.class)
class AddColumnPatchAgentAdapterTest {

    @Mock private LlmGateway llmGateway;
    @InjectMocks private AddColumnPatchAgent addColumnPatchAgent;
    private AddColumnPatchAgentAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AddColumnPatchAgentAdapter(addColumnPatchAgent);
    }

    private ImpactSubgraph subgraphWithFiles() {
        return ImpactSubgraph.builder()
                .targetNodeId("t-1")
                .impactedFiles(List.of("backend/ChangeTask.java", "db/migration/V_next.sql"))
                .build();
    }

    @Test
    void toPatchPlan_llmReturnsNull_fallbackManualReviewPlan() {
        when(llmGateway.callWithEnvelope(any(), eq("add-column-patch"), anyMap(), eq(PatchPlan.class)))
                .thenReturn(null);

        PatchPlan plan = adapter.toPatchPlan("task-1", "p1", "lg_change_task", "priority",
                "VARCHAR(32)", true, null, subgraphWithFiles(), List.of("e1"));

        assertEquals("ADD_COLUMN", plan.getTaskType());
        assertTrue(plan.isManualReviewNeeded(), "LLM 不可用降级须强制人工复核");
        assertFalse(plan.getPatches().isEmpty(), "降级须产出最小骨架 patch");
        assertEquals("task-1", plan.getTaskId());
        verify(llmGateway).callWithEnvelope(any(), eq("add-column-patch"), anyMap(), eq(PatchPlan.class));
    }

    @Test
    void toPatchPlan_notNullNoDefault_forcesManualReview() {
        PatchPlan llmPlan = PatchPlan.builder()
                .taskType("ADD_COLUMN")
                .riskLevel("LOW")
                .patches(List.of(PatchPlan.Patch.builder()
                        .filePath("db/migration/V_next.sql")
                        .changeType("CREATE")
                        .patchText("ALTER TABLE lg_change_task ADD COLUMN priority INT NOT NULL")
                        .evidenceIds(List.of("e1"))
                        .build()))
                .build();
        when(llmGateway.callWithEnvelope(any(), eq("add-column-patch"), anyMap(), eq(PatchPlan.class)))
                .thenReturn(llmPlan);

        // nullable=false + defaultValue=null → 强制 manualReviewNeeded
        PatchPlan plan = adapter.toPatchPlan("task-1", "p1", "lg_change_task", "priority",
                "INT", false, null, subgraphWithFiles(), List.of("e1"));

        assertEquals("ADD_COLUMN", plan.getTaskType());
        assertTrue(plan.isManualReviewNeeded(), "NOT NULL 无默认值须强制人工复核");
    }

    @Test
    void toPatchPlan_nullableWithDefault_noForceReview() {
        PatchPlan llmPlan = PatchPlan.builder()
                .taskType("ADD_COLUMN")
                .riskLevel("LOW")
                .manualReviewNeeded(false)
                .patches(List.of(PatchPlan.Patch.builder()
                        .filePath("db/migration/V_next.sql")
                        .changeType("CREATE")
                        .patchText("ALTER TABLE lg_change_task ADD COLUMN priority VARCHAR(32)")
                        .evidenceIds(List.of("e1"))
                        .build()))
                .build();
        when(llmGateway.callWithEnvelope(any(), eq("add-column-patch"), anyMap(), eq(PatchPlan.class)))
                .thenReturn(llmPlan);

        PatchPlan plan = adapter.toPatchPlan("task-1", "p1", "lg_change_task", "priority",
                "VARCHAR(32)", true, null, subgraphWithFiles(), List.of("e1"));

        assertEquals("ADD_COLUMN", plan.getTaskType());
        assertFalse(plan.isManualReviewNeeded(), "可空字段不应强制人工复核");
        assertEquals("AddColumnPatchAgent", plan.getGeneratedBy());
    }
}
