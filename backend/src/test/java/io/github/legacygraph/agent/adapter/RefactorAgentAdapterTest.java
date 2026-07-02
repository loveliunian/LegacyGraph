package io.github.legacygraph.agent.adapter;

import io.github.legacygraph.agent.RefactorAgent;
import io.github.legacygraph.dto.RefactorSuggestion;
import io.github.legacygraph.dto.graph.PatchPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RefactorAgentAdapter 单元测试。
 * 验证 RefactorAgent → PatchPlan 适配转换逻辑，含风险归一化。
 */
@ExtendWith(MockitoExtension.class)
class RefactorAgentAdapterTest {

    @Mock
    private RefactorAgent refactorAgent;

    @InjectMocks
    private RefactorAgentAdapter adapter;

    /**
     * 测试正常重构生成 PatchPlan 草案。
     */
    @Test
    void toPatchPlan_producesDraftPlan() {
        RefactorSuggestion suggestion = new RefactorSuggestion();
        suggestion.setSummary("拆分为 OrderValidator 和 OrderPersister");
        suggestion.setRefactoredSkeleton("class OrderValidator { ... }");
        suggestion.setRisk("MEDIUM");
        when(refactorAgent.suggest(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(suggestion);

        PatchPlan plan = adapter.toPatchPlan(
                "task-1", "project-1", "OrderService",
                "GOD_CLASS", "public class OrderService {}",
                "/src/OrderService.java", List.of("ev-1"));

        assertNotNull(plan);
        assertEquals("task-1", plan.getTaskId());
        assertEquals("REFACTOR", plan.getTaskType());
        assertEquals("MEDIUM", plan.getRiskLevel());
        assertTrue(plan.isManualReviewNeeded());
        assertEquals("RefactorAgent", plan.getGeneratedBy());
        assertEquals(1, plan.getPatches().size());
        assertEquals(List.of("STATIC", "UNIT"), plan.getValidationGates());
        assertEquals("MODIFY", plan.getPatches().get(0).getChangeType());
    }

    /**
     * 测试高风险归一化。
     */
    @Test
    void toPatchPlan_highRisk_normalizedToHIGH() {
        RefactorSuggestion suggestion = new RefactorSuggestion();
        suggestion.setSummary("高风险重构");
        suggestion.setRisk("高");
        when(refactorAgent.suggest(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(suggestion);

        PatchPlan plan = adapter.toPatchPlan(
                "task-2", "project-1", "OrderService",
                "GOD_CLASS", "code", "/src/OrderService.java", List.of());

        assertEquals("HIGH", plan.getRiskLevel());
    }

    /**
     * 测试 null 建议时的兜底行为。
     */
    @Test
    void toPatchPlanWithNullSuggestion_usesDefaults() {
        when(refactorAgent.suggest(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        PatchPlan plan = adapter.toPatchPlan(
                "task-3", "project-1", "OrderService",
                "GOD_CLASS", "code", "/src/OrderService.java", List.of());

        assertNotNull(plan);
        assertEquals("MEDIUM", plan.getRiskLevel());
        assertTrue(plan.isManualReviewNeeded());
        assertNull(plan.getPatches().get(0).getPatchText());
        assertEquals("重构建议", plan.getImpactedFiles().get(0).getReason());
    }

    /**
     * 测试低风险归一化。
     */
    @Test
    void toPatchPlan_lowRisk_normalizedToLOW() {
        RefactorSuggestion suggestion = new RefactorSuggestion();
        suggestion.setSummary("低风险重命名");
        suggestion.setRisk("低");
        when(refactorAgent.suggest(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(suggestion);

        PatchPlan plan = adapter.toPatchPlan(
                "task-4", "project-1", "OrderService",
                "MAGIC_NUMBER", "code", "/src/OrderService.java", List.of());

        assertEquals("LOW", plan.getRiskLevel());
    }
}
