package io.github.legacygraph.agent;

import io.github.legacygraph.dto.TestFailureAnalysis;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.llm.LlmGateway;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * TestFailureAnalysisAgent - 测试失败根因分析。
 * Phase 3-1: {@link #analyzeFromEnvelope(AgentEnvelope)} 合约入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestFailureAnalysisAgent {

    private final LlmGateway llmGateway;

    @Data
    public static class FailureContext {
        private String projectId, caseName, targetNode, request, response,
                errorMessage, graphPath, recentTrace;
    }

    /** Phase 3-1: AgentEnvelope 合约入口 */
    public TestFailureAnalysis analyzeFromEnvelope(AgentEnvelope<FailureContext> env) {
        return analyze(env.getInput());
    }

    public TestFailureAnalysis analyze(FailureContext ctx) {
        Map<String, String> variables = new HashMap<>();
        variables.put("caseName", nz(ctx.getCaseName()));
        variables.put("targetNode", nz(ctx.getTargetNode()));
        variables.put("request", nz(ctx.getRequest()));
        variables.put("response", nz(ctx.getResponse()));
        variables.put("errorMessage", nz(ctx.getErrorMessage()));
        variables.put("graphPath", nz(ctx.getGraphPath()));
        variables.put("recentTrace", nz(ctx.getRecentTrace()));
        return llmGateway.callWithTemplate(ctx.getProjectId(), "test-failure-analysis",
                variables, TestFailureAnalysis.class);
    }

    private String nz(String s) { return s != null ? s : ""; }
}
