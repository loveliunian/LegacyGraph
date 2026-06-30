package io.github.legacygraph.agent;

import io.github.legacygraph.dto.ChangeImpactAnalysis;
import io.github.legacygraph.dto.MigrationConversion;
import io.github.legacygraph.dto.PrDescription;
import io.github.legacygraph.dto.RefactorSuggestion;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 4 后置能力 Agent 测试：重构建议 / 变更影响 / 迁移转换 / PR 描述。
 */
@ExtendWith(MockitoExtension.class)
class Phase4AgentsTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private RefactorAgent refactorAgent;
    @InjectMocks
    private ChangeImpactAgent changeImpactAgent;
    @InjectMocks
    private MigrationAgent migrationAgent;
    @InjectMocks
    private PrDescriptionAgent prDescriptionAgent;

    @Captor
    private ArgumentCaptor<Map<String, String>> varsCaptor;

    @Test
    void testRefactorAgent() {
        RefactorSuggestion expected = new RefactorSuggestion();
        expected.setRisk("MEDIUM");
        when(llmGateway.callWithTemplate(eq("p1"), eq("refactor-suggestion"), anyMap(),
                eq(RefactorSuggestion.class))).thenReturn(expected);

        RefactorSuggestion result = refactorAgent.suggest("p1", "OrderService", "GOD_CLASS", "class OrderService {}");

        assertEquals("MEDIUM", result.getRisk());
        verify(llmGateway).callWithTemplate(eq("p1"), eq("refactor-suggestion"),
                varsCaptor.capture(), eq(RefactorSuggestion.class));
        assertEquals("OrderService", varsCaptor.getValue().get("target"));
        assertEquals("GOD_CLASS", varsCaptor.getValue().get("smellType"));
    }

    @Test
    void testChangeImpactAgent() {
        ChangeImpactAnalysis expected = new ChangeImpactAnalysis();
        expected.setChangeType("BREAKING_CHANGE");
        expected.setSeverity("HIGH");
        when(llmGateway.callWithTemplate(eq("p1"), eq("change-impact"), anyMap(),
                eq(ChangeImpactAnalysis.class))).thenReturn(expected);

        ChangeImpactAnalysis result = changeImpactAgent.analyze("p1", "OrderService#create",
                "修改返回类型", "api:/order/create");

        assertEquals("BREAKING_CHANGE", result.getChangeType());
        verify(llmGateway).callWithTemplate(eq("p1"), eq("change-impact"),
                varsCaptor.capture(), eq(ChangeImpactAnalysis.class));
        assertEquals("OrderService#create", varsCaptor.getValue().get("changeTarget"));
        assertEquals("api:/order/create", varsCaptor.getValue().get("dependencies"));
    }

    @Test
    void testMigrationAgent_DefaultsDirectionAndRules() {
        when(llmGateway.callWithTemplate(eq("p1"), eq("migration-convert"), anyMap(),
                eq(MigrationConversion.class))).thenReturn(new MigrationConversion());

        migrationAgent.convert("p1", null, "Entity.java", "import javax.persistence.Entity;", null);

        verify(llmGateway).callWithTemplate(eq("p1"), eq("migration-convert"),
                varsCaptor.capture(), eq(MigrationConversion.class));
        assertEquals("SpringBoot2_to_3", varsCaptor.getValue().get("migrationDirection"));
        assertEquals("（无）", varsCaptor.getValue().get("customRules"));
        assertEquals("Entity.java", varsCaptor.getValue().get("sourcePath"));
    }

    @Test
    void testPrDescriptionAgent() {
        PrDescription expected = new PrDescription();
        expected.setChangeType("feat");
        expected.setCommitMessage("feat(order): 批量取消");
        when(llmGateway.callWithTemplate(eq("p1"), eq("pr-description"), anyMap(),
                eq(PrDescription.class))).thenReturn(expected);

        PrDescription result = prDescriptionAgent.generate("p1", "feature/cancel", "JIRA-123", "diff...");

        assertEquals("feat", result.getChangeType());
        verify(llmGateway).callWithTemplate(eq("p1"), eq("pr-description"),
                varsCaptor.capture(), eq(PrDescription.class));
        assertEquals("feature/cancel", varsCaptor.getValue().get("branch"));
        assertEquals("JIRA-123", varsCaptor.getValue().get("issue"));
    }
}
