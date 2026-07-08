package io.github.legacygraph.agent.adapter;

import io.github.legacygraph.agent.MigrationAgent;
import io.github.legacygraph.dto.MigrationConversion;
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
 * MigrationAgentAdapter 单元测试。
 * 验证 MigrationAgent → PatchPlan 适配转换逻辑。
 */
@ExtendWith(MockitoExtension.class)
class MigrationAgentAdapterTest {

    @Mock
    private MigrationAgent migrationAgent;

    @InjectMocks
    private MigrationAgentAdapter adapter;

    /**
     * 测试正常迁移转换生成 PatchPlan 草案。
     */
    @Test
    void toPatchPlan_producesDraftPlan() {
        MigrationConversion conversion = new MigrationConversion();
        conversion.setMigratedCode("public class NewConfig {}");
        conversion.setSummary("Spring Boot 3 迁移");
        conversion.setManualReviewNeeded(java.util.List.of());
        when(migrationAgent.convert(anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(conversion);

        PatchPlan plan = adapter.toPatchPlan(
                "task-1", "project-1", "SpringBoot2_to_3",
                "/src/main/java/Config.java", "public class Config {}",
                "禁用默认登录页", List.of("ev-1", "ev-2"));

        assertNotNull(plan);
        assertEquals("task-1", plan.getTaskId());
        assertEquals("UPGRADE", plan.getTaskType());
        assertEquals("HIGH", plan.getRiskLevel());
        assertFalse(plan.isManualReviewNeeded());
        assertEquals("MigrationAgent", plan.getGeneratedBy());
        assertEquals(1, plan.getPatches().size());
        assertEquals(List.of("STATIC", "UNIT", "MIGRATION"), plan.getValidationGates());
        verify(migrationAgent).convert("project-1", "SpringBoot2_to_3",
                "/src/main/java/Config.java", "public class Config {}", "禁用默认登录页");
    }

    /**
     * 测试 null 转换结果时 manualReviewNeeded=true。
     */
    @Test
    void toPatchPlanWithNullConversion_marksManualReview() {
        when(migrationAgent.convert(anyString(), anyString(), anyString(),
                anyString(), any()))
                .thenReturn(null);

        PatchPlan plan = adapter.toPatchPlan(
                "task-2", "project-1", "SpringBoot2_to_3",
                "/src/Config.java", "code", null, List.of());

        assertNotNull(plan);
        assertTrue(plan.isManualReviewNeeded());
        assertEquals("UPGRADE", plan.getTaskType());
        verify(migrationAgent).convert(anyString(), anyString(), anyString(),
                anyString(), any());
    }

    /**
     * 测试 ManualReviewNeeded 非空时标记人工审核。
     */
    @Test
    void toPatchPlanWhenManualReviewNeeded_isTrue() {
        MigrationConversion conversion = new MigrationConversion();
        conversion.setManualReviewNeeded(java.util.List.of("部分规则需人工确认"));
        when(migrationAgent.convert(anyString(), anyString(), anyString(),
                anyString(), any()))
                .thenReturn(conversion);

        PatchPlan plan = adapter.toPatchPlan("task-3", "project-1",
                "SpringBoot2_to_3", "/src/Config.java", "code", null, List.of());

        assertTrue(plan.isManualReviewNeeded());
    }
}
