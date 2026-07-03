package io.github.legacygraph.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class Neo4jConfig {

    @Value("${spring.neo4j.uri}")
    private String uri;

    @Value("${spring.neo4j.authentication.username}")
    private String username;

    @Value("${spring.neo4j.authentication.password}")
    private String password;

    @Bean
    public Driver neo4jDriver() {
        Config config = Config.builder()
                .withConnectionTimeout(10, TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(30, TimeUnit.SECONDS)
                .withMaxTransactionRetryTime(30, TimeUnit.SECONDS)
                // 连接池：扫描期间最多16并发(adapter扫描Semaphore)，加上管理操作留余量
                .withMaxConnectionPoolSize(20)
                // 连接最大存活时间：扫描全流程最长1.5h，设为2h避免中途过期重连
                .withMaxConnectionLifetime(120, TimeUnit.MINUTES)
                // 空闲连接存活检测：每5分钟检查一次，及时回收死连接
                .withConnectionLivenessCheckTimeout(5, TimeUnit.MINUTES)
                // 泄漏检测：获取连接超过30s未归还则告警
                .withLeakedSessionsLogging()
                .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
    }
}
