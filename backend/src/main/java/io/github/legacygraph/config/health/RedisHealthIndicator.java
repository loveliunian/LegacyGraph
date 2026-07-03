package io.github.legacygraph.config.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisConnectionFactory redisConnectionFactory;

    @Override
    public Health health() {
        try (var connection = redisConnectionFactory.getConnection()) {
            String pong = connection.ping();
            return Health.up()
                    .withDetail("cache", "Redis")
                    .withDetail("ping", pong)
                    .build();
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            return Health.down()
                    .withDetail("cache", "Redis")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
