package io.github.legacygraph.agent;

import io.github.legacygraph.dto.ChangeImpactAnalysis;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChangeImpactAgent 单元测试。
 * 验证 Phase 3-1 AgentEnvelope 合约版本与旧版 API 兼容。
 */
@ExtendWith(MockitoExtension.class)
class ChangeImpactAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private ChangeImpactAgent agent;

    /**
     * 测试 AgentEnvelope 合约版本的变更影响分析。
     */
    @Test
    void analyzeWithEnvelope_delegatesToLlmGateway() {
        ChangeImpactAnalysis expected = new ChangeImpactAnalysis();
        expected.setChangeType("BREAKING");
        when(llmGateway.callWithEnvelope(any(), eq("change-impact"), anyMap(),
                eq(ChangeImpactAnalysis.class)))
                .thenReturn(expected);

        AgentEnvelope<ChangeImpactAgent.ChangeImpactInput> envelope = AgentEnvelope
                .<ChangeImpactAgent.ChangeImpactInput>builder()
                .projectId("project-1")
                .agentType("ChangeImpactAgent")
                .input(ChangeImpactAgent.ChangeImpactInput.builder()
                        .changeTarget("OrderService")
                        .changeDescription("修改 createOrder 方法签名")
                        .dependencies("PaymentService, InventoryService")
                        .build())
                .build();

        ChangeImpactAnalysis result = agent.analyze(envelope);

        assertNotNull(result);
        assertEquals("BREAKING", result.getChangeType());
        verify(llmGateway).callWithEnvelope(any(), eq("change-impact"), anyMap(),
                eq(ChangeImpactAnalysis.class));
    }

    /**
     * 测试空输入时返回 null。
     */
    @Test
    void analyzeWithNullInput_returnsNull() {
        AgentEnvelope<ChangeImpactAgent.ChangeImpactInput> envelope = AgentEnvelope
                .<ChangeImpactAgent.ChangeImpactInput>builder()
                .projectId("project-1")
                .agentType("ChangeImpactAgent")
                .input(null)
                .build();

        ChangeImpactAnalysis result = agent.analyze(envelope);

        assertNull(result);
        verify(llmGateway, never()).callWithEnvelope(any(), anyString(), anyMap(), any());
    }

    /**
     * 测试旧版 4 参数 API 兼容。
     */
    @Test
    void analyzeLegacy_delegatesToLlmGateway() {
        ChangeImpactAnalysis expected = new ChangeImpactAnalysis();
        expected.setChangeType("NON_BREAKING");
        when(llmGateway.callWithTemplate(eq("project-1"), eq("change-impact"), anyMap(),
                eq(ChangeImpactAnalysis.class)))
                .thenReturn(expected);

        ChangeImpactAnalysis result = agent.analyze(
                "project-1", "OrderService", "优化查询性能", "PaymentService");

        assertNotNull(result);
        assertEquals("NON_BREAKING", result.getChangeType());
        verify(llmGateway).callWithTemplate(eq("project-1"), eq("change-impact"), anyMap(),
                eq(ChangeImpactAnalysis.class));
    }
}
