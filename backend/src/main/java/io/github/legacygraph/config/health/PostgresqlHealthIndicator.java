package io.github.legacygraph.config.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostgresqlHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public Health health() {
        try (var connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            if (!valid) {
                return Health.down()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("error", "connection validation failed")
                        .build();
            }
            return Health.up()
                    .withDetail("database", "PostgreSQL")
                    .withDetail("url", connection.getMetaData().getURL())
                    .build();
        } catch (Exception e) {
            log.error("PostgreSQL health check failed", e);
            return Health.down()
                    .withDetail("database", "PostgreSQL")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
