package io.github.legacygraph.config.health;

import io.github.legacygraph.understanding.tool.ToolHealth;
import io.github.legacygraph.understanding.tool.ToolHealthService;
import io.github.legacygraph.understanding.tool.ToolStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolHealthIndicator implements HealthIndicator {

    private final ToolHealthService toolHealthService;

    @Value("${legacy-graph.health.default-project-id:default}")
    private String defaultProjectId;

    @Override
    public Health health() {
        try {
            List<ToolHealth> tools = toolHealthService.checkAllTools(defaultProjectId);
            long ready = tools.stream().filter(t -> t.getStatus() == ToolStatus.READY).count();
            long unavailable = tools.stream().filter(t -> t.getStatus() == ToolStatus.UNAVAILABLE).count();
            long stale = tools.stream().filter(t -> t.getStatus() == ToolStatus.STALE).count();
            Health.Builder builder = unavailable > 0
                    ? Health.down()
                    : (stale > 0 ? Health.status(Status.UNKNOWN) : Health.up());
            return builder
                    .withDetail("toolKind", "MCP")
                    .withDetail("registeredTools", tools.size())
                    .withDetail("readyTools", ready)
                    .withDetail("unavailableTools", unavailable)
                    .withDetail("staleTools", stale)
                    .build();
        } catch (Exception e) {
            log.error("MCP tool health check failed", e);
            return Health.down()
                    .withDetail("toolKind", "MCP")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
