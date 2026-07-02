package io.github.legacygraph.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.rag.GraphRagPlan;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.llm.LlmCallException;
import io.github.legacygraph.llm.LlmGateway;
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
 * GraphRagPlannerAgent 单元测试。
 * 验证查询规划、降级策略与空输入处理。
 */
@ExtendWith(MockitoExtension.class)
class GraphRagPlannerAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private GraphRagPlannerAgent agent;

    /**
     * 测试正常规划：委托 LLM 并返回 GraphRagPlan。
     */
    @Test
    void plan_delegatesToLlmAndReturnsGraphRagPlan() {
        GraphRagPlan llmPlan = new GraphRagPlan();
        GraphRagPlan.SubQuestion sq = new GraphRagPlan.SubQuestion();
        sq.setTargetType("Table");
        sq.setQuery("订单创建的表结构是什么？");
        sq.setPriority("HIGH");
        llmPlan.setSubQuestions(List.of(sq));
        GraphRagPlan.ClaimQuery cq = new GraphRagPlan.ClaimQuery();
        cq.setSubjectType("Feature");
        cq.setSubjectKey("feature:订单创建");
        llmPlan.setClaimQueries(List.of(cq));
        llmPlan.setNeedsHumanReview(false);
        when(llmGateway.callWithEnvelope(any(), eq("graph-rag-planner"), anyMap(),
                eq(GraphRagPlan.class)))
                .thenReturn(llmPlan);

        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setSubjectType("Feature");
        claim.setSubjectKey("feature:订单创建");
        claim.setPredicate("HAS_API");

        GraphRagPlan result = agent.plan("project-1", "订单创建流程", List.of(claim));

        assertNotNull(result);
        assertFalse(result.isNeedsHumanReview());
        assertEquals("订单创建流程", result.getQuestion());
        verify(llmGateway).callWithEnvelope(any(), eq("graph-rag-planner"), anyMap(),
                eq(GraphRagPlan.class));
    }

    /**
     * 测试空 question — 应返回 emptyPlan。
     */
    @Test
    void planWithEmptyQuestion_returnsEmptyPlan() {
        GraphRagPlan result = agent.plan("project-1", "   ", List.of());

        assertNotNull(result);
        assertTrue(result.isNeedsHumanReview());
        assertEquals("   ", result.getQuestion());
        verify(llmGateway, never()).callWithEnvelope(any(), anyString(), anyMap(), any());
    }

    /**
     * 测试 LLM 调用失败时的降级计划。
     */
    @Test
    void planWhenLlmFails_returnsFallbackPlan() {
        when(llmGateway.callWithEnvelope(any(), eq("graph-rag-planner"), anyMap(),
                eq(GraphRagPlan.class)))
                .thenThrow(new LlmCallException("调用失败", new RuntimeException("超时"), true, 1L));

        GraphRagPlan result = agent.plan("project-1", "用户权限", List.of());

        assertNotNull(result);
        assertTrue(result.isNeedsHumanReview());
        assertTrue(result.getReasoning().contains("LLM 调用失败"));
        assertEquals("用户权限", result.getQuestion());
    }
}
