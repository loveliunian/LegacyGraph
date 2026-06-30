package io.github.legacygraph.config;

import io.github.legacygraph.dao.Neo4jGraphDao;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Neo4j 初始化 — 应用启动时自动创建约束和索引
 */
@Slf4j
@Component
public class Neo4jInitializer {

    private final Neo4jGraphDao neo4jGraphDao;

    public Neo4jInitializer(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    @PostConstruct
    public void init() {
        try {
            neo4jGraphDao.createConstraints();
            neo4jGraphDao.createIndexes();
            log.info("Neo4j constraints and indexes initialized");
        } catch (Exception e) {
            log.warn("Neo4j initialization failed (may be normal if Neo4j is not running): {}", e.getMessage());
        }
    }
}
