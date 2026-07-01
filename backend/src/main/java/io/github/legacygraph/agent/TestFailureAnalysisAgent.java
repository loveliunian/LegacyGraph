package io.github.legacygraph.agent;

import io.github.legacygraph.dto.TestFailureAnalysis;
import io.github.legacygraph.llm.LlmGateway;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * TestFailureAnalysisAgent - 测试失败根因分析与复测建议
 *
 * <p>输入测试请求/响应/错误信息/目标节点/上下游图谱路径/最近 trace，
 * 输出可能根因、关联制品、排查步骤、是否降低置信度与重跑范围。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestFailureAnalysisAgent {

    private final LlmGateway llmGateway;

    @Data
    public static class FailureContext {
        private String projectId;
        private String caseName;
        private String targetNode;
        private String request;
        private String response;
        private String errorMessage;
        private String graphPath;
        private String recentTrace;
    }

    /**
     * 分析测试失败根因
     */
    public TestFailureAnalysis analyze(FailureContext ctx) {
        Map<String, String> variables = new HashMap<>();
        variables.put("caseName", nullToEmpty(ctx.getCaseName()));
        variables.put("targetNode", nullToEmpty(ctx.getTargetNode()));
        variables.put("request", nullToEmpty(ctx.getRequest()));
        variables.put("response", nullToEmpty(ctx.getResponse()));
        variables.put("errorMessage", nullToEmpty(ctx.getErrorMessage()));
        variables.put("graphPath", nullToEmpty(ctx.getGraphPath()));
        variables.put("recentTrace", nullToEmpty(ctx.getRecentTrace()));

        return llmGateway.callWithTemplate(ctx.getProjectId(), "test-failure-analysis",
                variables, TestFailureAnalysis.class);
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
