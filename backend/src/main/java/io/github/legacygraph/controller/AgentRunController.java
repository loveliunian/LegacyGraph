package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.service.AgentRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/agents/runs")
@RequiredArgsConstructor
public class AgentRunController {

    private static final int DEFAULT_LIMIT = 50;

    private final AgentRunService agentRunService;

    @GetMapping("/history")
    public Result<Map<String, Object>> history(
            @RequestParam String projectId,
            @RequestParam(required = false) String agentType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return Result.ok(agentRunService.history(projectId, agentType, status, limit));
    }
}
