package io.github.legacygraph.service.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 缓存层启动期健康检查器。
 *
 * <p>独立成 Component（而非放在 {@link CacheService} 内），原因：CacheService 在测试中常被
 * {@code Mockito.mock(CacheService.class)} mock，内联 mock-maker 会解析类上注解引用的类型，
 * 若 CacheService 引用 {@code ApplicationReadyEvent} 会报 "Type not found" 导致 mock 失败。
 * 把健康检查移到这个不被 mock 的组件里，既保留启动告警能力，又不破坏 CacheService 的可 mock 性。</p>
 *
 * <p>背景：Spring Data Redis (Lettuce) 懒连接——Redis 不通时应用照常启动，首次命令才失败、
 * 被 CacheService 各 try/catch 静默吞掉，导致整层缓存降级却无人察觉（曾出现：LLM 抽取缓存 0 命中、
 * 重扫未变文档仍重调 LLM 多耗 747s）。此检查在应用就绪后做一次 PING，连不通时 ERROR 级告警，不阻断启动。</p>
 */
@Slf4j
@Component
public class CacheHealthChecker {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheHealthChecker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void healthCheck() {
        try {
            try (var conn = stringRedisTemplate.getConnectionFactory().getConnection()) {
                conn.ping();
            }
            log.info("Redis cache layer connected (PING ok) — caching active");
        } catch (Exception e) {
            log.error("Redis UNREACHABLE at startup — cache layer will degrade silently: every get/put will miss/fail. "
                    + "LLM extraction cache, graph read cache etc. will all be no-ops until Redis is reachable. "
                    + "Fix Redis connectivity (host/port/network/auth) to restore caching. Cause: {}", e.toString(), e);
        }
    }
}
