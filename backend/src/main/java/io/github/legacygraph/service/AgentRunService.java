package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.legacygraph.entity.AgentRun;
import io.github.legacygraph.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AgentRunService {

    private static final int MAX_LIMIT = 200;

    private final AgentRunRepository repository;

    public Map<String, Object> history(String projectId, String agentType, String status, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        QueryWrapper<AgentRun> query = new QueryWrapper<AgentRun>()
                .eq("project_id", projectId)
                .orderByDesc("created_at")
                .last("LIMIT " + safeLimit);
        if (hasText(agentType)) {
            query.eq("agent_type", agentType);
        }

        List<Map<String, Object>> runs = repository.selectList(query).stream()
                .map(this::toHistoryRow)
                .filter(row -> !hasText(status) || Objects.equals(row.get("status"), status.toUpperCase(Locale.ROOT)))
                .toList();
        return Map.of("list", runs);
    }

    private Map<String, Object> toHistoryRow(AgentRun run) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", run.getId());
        row.put("contractId", run.getContractId());
        row.put("projectId", run.getProjectId());
        row.put("agentType", run.getAgentType());
        row.put("agentName", run.getAgentName());
        row.put("status", deriveStatus(run));
        row.put("model", run.getModel());
        row.put("promptTokens", run.getPromptTokens());
        row.put("completionTokens", run.getCompletionTokens());
        row.put("costUsd", run.getCostUsd());
        row.put("retryCount", run.getRetryCount());
        row.put("selfCorrectionCount", run.getSelfCorrectionCount());
        row.put("qualityScore", run.getQualityScore());
        row.put("needsHumanReview", Objects.equals(run.getNeedsHumanReview(), 1));
        row.put("durationMs", durationMs(run));
        row.put("input", run.getMetadata());
        row.put("output", run.getUsedEvidenceIds());
        row.put("createdAt", run.getCreatedAt() != null ? run.getCreatedAt() : run.getStartedAt());
        row.put("startedAt", run.getStartedAt());
        row.put("finishedAt", run.getFinishedAt());
        return row;
    }

    private String deriveStatus(AgentRun run) {
        if (run.getFinishedAt() == null) {
            return "RUNNING";
        }
        if (Objects.equals(run.getNeedsHumanReview(), 1)) {
            return "REVIEW";
        }
        return "SUCCESS";
    }

    private Long durationMs(AgentRun run) {
        if (run.getStartedAt() == null || run.getFinishedAt() == null) {
            return null;
        }
        return Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
