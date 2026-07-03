package io.github.legacygraph.config.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Neo4j 健康检查指示器
 * 
 * 检测项：
 * 1. Driver 连接可用性
 * 2. 数据库可访问性（执行简单查询）
 * 3. 连接池状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Neo4jHealthIndicator implements HealthIndicator {

    private final Driver neo4jDriver;

    @Override
    public Health health() {
        try {
            // 验证连接
            neo4jDriver.verifyConnectivity();
            
            // 执行简单查询测试数据库可用性
            try (Session session = neo4jDriver.session()) {
                Result result = session.run("RETURN 1 AS test");
                result.consume();
            }
            
            // 获取连接池信息
            var poolMetrics = neo4jDriver.metrics().connectionPoolMetrics();
            int inUse = poolMetrics.stream().mapToInt(m -> m.inUse()).sum();
            int idle = poolMetrics.stream().mapToInt(m -> m.idle()).sum();
            
            return Health.up()
                    .withDetail("database", "Neo4j")
                    .withDetail("status", "available")
                    .withDetail("connectionsInUse", inUse)
                    .withDetail("connectionsIdle", idle)
                    .build();
                    
        } catch (Exception e) {
            log.error("Neo4j health check failed", e);
            return Health.down()
                    .withDetail("database", "Neo4j")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
