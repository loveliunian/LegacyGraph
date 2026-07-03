package io.github.legacygraph.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Flyway 手动配置
 * <p>
 * Spring Boot 4.x 移除了 Flyway 自动配置（FlywayAutoConfiguration 不在
 * spring-boot-autoconfigure:4.0.7 中），因此需要手动配置 Flyway bean
 * 并触发迁移。
 * </p>
 */
@Configuration
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Value("${spring.flyway.locations:classpath:db/migration}")
    private String locations;

    @Value("${spring.flyway.baseline-on-migrate:false}")
    private boolean baselineOnMigrate;

    @Value("${spring.flyway.baseline-version:1}")
    private String baselineVersion;

    @Value("${spring.flyway.table:flyway_schema_history}")
    private String table;

    @Value("${spring.flyway.clean-disabled:true}")
    private boolean cleanDisabled;

    @Value("${spring.flyway.validate-on-migrate:true}")
    private boolean validateOnMigrate;

    @Value("${spring.flyway.out-of-order:false}")
    private boolean outOfOrder;

    @Bean
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .table(table)
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion(baselineVersion)
                .cleanDisabled(cleanDisabled)
                .validateOnMigrate(validateOnMigrate)
                .outOfOrder(outOfOrder)
                .placeholderReplacement(false)
                .load();

        log.info("开始执行 Flyway 数据库迁移...");
        int migrationsApplied = flyway.migrate().migrationsExecuted;
        log.info("Flyway 迁移完成，执行了 {} 个迁移脚本", migrationsApplied);

        return flyway;
    }
}
