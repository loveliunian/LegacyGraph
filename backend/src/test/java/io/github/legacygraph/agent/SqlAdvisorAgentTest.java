package io.github.legacygraph.agent;

import io.github.legacygraph.dto.SqlAdvisorResult;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SqlAdvisorAgent 测试。
 */
@ExtendWith(MockitoExtension.class)
class SqlAdvisorAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private SqlAdvisorAgent sqlAdvisorAgent;

    @Captor
    private ArgumentCaptor<Map<String, String>> variablesCaptor;

    @Test
    void testAnalyze_PassesVariablesAndReturnsResult() {
        SqlAdvisorResult expected = new SqlAdvisorResult();
        expected.setSqlKey("UserMapper.selectAll");
        expected.setOverallRisk("MEDIUM");
        SqlAdvisorResult.SqlIssue issue = new SqlAdvisorResult.SqlIssue();
        issue.setIssueType("SELECT_STAR");
        issue.setSeverity("MEDIUM");
        expected.setIssues(List.of(issue));

        when(llmGateway.callWithTemplate(eq("proj-1"), eq("sql-advisor"), anyMap(), eq(SqlAdvisorResult.class)))
                .thenReturn(expected);

        SqlAdvisorResult result = sqlAdvisorAgent.analyze("proj-1", "UserMapper.selectAll",
                "SELECT * FROM t_user", "t_user(id PK, name)");

        assertNotNull(result);
        assertEquals("MEDIUM", result.getOverallRisk());
        assertEquals(1, result.getIssues().size());

        verify(llmGateway).callWithTemplate(eq("proj-1"), eq("sql-advisor"),
                variablesCaptor.capture(), eq(SqlAdvisorResult.class));
        Map<String, String> vars = variablesCaptor.getValue();
        assertEquals("UserMapper.selectAll", vars.get("sqlKey"));
        assertEquals("SELECT * FROM t_user", vars.get("sql"));
        assertEquals("t_user(id PK, name)", vars.get("schemaInfo"));
    }

    @Test
    void testAnalyze_NullSchemaInfo_DefaultsToPlaceholder() {
        when(llmGateway.callWithTemplate(any(), eq("sql-advisor"), anyMap(), eq(SqlAdvisorResult.class)))
                .thenReturn(new SqlAdvisorResult());

        sqlAdvisorAgent.analyze("proj-1", "k", "SELECT 1", null);

        verify(llmGateway).callWithTemplate(any(), eq("sql-advisor"),
                variablesCaptor.capture(), eq(SqlAdvisorResult.class));
        assertEquals("（无表结构信息）", variablesCaptor.getValue().get("schemaInfo"));
    }
}
