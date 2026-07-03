package io.github.legacygraph.config.health;

import io.github.legacygraph.entity.LlmProvider;
import io.github.legacygraph.service.system.LlmProviderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmProviderHealthIndicator implements HealthIndicator {

    private final LlmProviderService llmProviderService;

    @Override
    public Health health() {
        try {
            LlmProvider provider = llmProviderService.getActiveDefault();
            if (provider == null) {
                return Health.down()
                        .withDetail("llm", "provider")
                        .withDetail("error", "no active provider")
                        .build();
            }
            return Health.up()
                    .withDetail("llm", "provider")
                    .withDetail("providerCode", provider.getProviderCode())
                    .withDetail("modelId", provider.getModelId())
                    .build();
        } catch (Exception e) {
            log.error("LLM provider health check failed", e);
            return Health.down()
                    .withDetail("llm", "provider")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
