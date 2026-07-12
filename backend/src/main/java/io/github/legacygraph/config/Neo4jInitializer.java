package io.github.legacygraph.config;

import io.github.legacygraph.dao.Neo4jGraphDao;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Neo4j 初始化 — 应用启动时创建约束，索引创建走后台异步 job。
 * <p>S3-T3: 启动期只补约束（同步，快速，保证建图幂等），索引创建走 @Async 后台 job，不阻塞启动。</p>
 */
@Slf4j
@Component
public class Neo4jInitializer {

    private final Neo4jGraphDao neo4jGraphDao;

    public Neo4jInitializer(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 启动期同步创建约束（快速，保证建图幂等）。
     */
    @PostConstruct
    public void init() {
        try {
            neo4jGraphDao.createConstraints();
            log.info("Neo4j constraints initialized");
        } catch (Exception e) {
            log.warn("Neo4j constraint initialization failed (may be normal if Neo4j is not running): {}", e.getMessage());
        }
    }

    /**
     * S3-T3: 索引创建走后台异步 job，不阻塞应用启动。
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void createIndexesAsync() {
        try {
            neo4jGraphDao.createIndexes();
            log.info("Neo4j indexes initialized (async)");
        } catch (Exception e) {
            log.warn("Neo4j index creation failed (background job): {}", e.getMessage());
        }
    }
}
