package io.github.legacygraph.service.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 通用缓存服务 — 对编程式 Redis 访问做统一封装与容错。
 *
 * <p>设计原则：缓存层故障绝不阻断业务。所有读写均 try/catch 包裹，
 * Redis 不可用时静默降级（读返回 null / 回源，写忽略）。</p>
 *
 * <p>所有 key 自动加 {@code lg:} 前缀，调用方传入业务子键即可（如 {@code "llm:result:..."}）。</p>
 */
@Slf4j
@Service
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** 首次缓存操作失败标志：首次打 ERROR+堆栈，后续降 debug，避免日志洪水但保留可观测性 */
    private final AtomicBoolean firstFailureLogged = new AtomicBoolean(false);

    /** 空值占位符：缓存 null 结果以防穿透 */
    private static final String NULL_PLACEHOLDER = "__NULL__";

    /** 不同 key 前缀的空值 TTL，降低热点穿透风险 */
    private static final Map<String, Duration> NULL_TTL_BY_PREFIX = Map.of(
            "graph:", Duration.ofMinutes(5),
            "report-", Duration.ofMinutes(10),
            "scan-progress:", Duration.ofSeconds(30)
    );
    private static final Duration DEFAULT_NULL_TTL = Duration.ofMinutes(5);

    /** GZIP 压缩阈值：超过此大小的值压缩后存入 Redis */
    private static final int COMPRESSION_THRESHOLD_BYTES = 1024;

    private Duration getNullTtl(String key) {
        return NULL_TTL_BY_PREFIX.entrySet().stream()
                .filter(e -> key.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(DEFAULT_NULL_TTL);
    }

    public CacheService(RedisTemplate<String, Object> redisTemplate,
                        StringRedisTemplate stringRedisTemplate,
                        ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 统一的缓存操作失败日志：首次打 ERROR+堆栈（便于定位根因），后续降 debug（避免日志洪水）。
     *
     * <p>注：启动期 Redis 连通性健康检查在 {@link CacheHealthChecker} 中，与 CacheService 分离，
     * 避免 CacheService 被 Mockito mock 时内联 mock-maker 解析 {@code ApplicationReadyEvent} 失败。</p>
     */
    private void logFailure(String op, String id, Exception e) {
        if (firstFailureLogged.compareAndSet(false, true)) {
            log.error("Redis op '{}' failed for id={} — cache layer degrading silently. "
                    + "Subsequent cache failures will be logged at DEBUG. Cause:", op, id, e);
        } else {
            log.debug("Redis op '{}' failed for id={}: {}", op, id, e.getMessage());
        }
    }

    private String fullKey(String key) {
        return RedisConfig.KEY_PREFIX + key;
    }

    // ==================== 对象缓存（JSON） ====================

    /**
     * 读取并反序列化为目标类型；未命中或异常返回 null（降级）。
     */
    public <T> T get(String key, Class<T> type) {
        try {
            Object raw = redisTemplate.opsForValue().get(fullKey(key));
            if (raw == null) {
                return null;
            }
            // 命中空值缓存，视为未命中（防穿透）
            if (NULL_PLACEHOLDER.equals(raw)) {
                return null;
            }
            if (type.isInstance(raw)) {
                return type.cast(raw);
            }
            // 经由 JSON 多态可能已是目标类型；否则做一次转换兜底
            return objectMapper.convertValue(raw, type);
        } catch (Exception e) {
            logFailure("get", key, e);
            return null;
        }
    }

    /**
     * 写入对象并设置 TTL；异常忽略（不阻断业务）。
     * null 值写入空占位符 {@link #NULL_PLACEHOLDER}，短 TTL 防缓存穿透。
     */
    public void put(String key, Object value, Duration ttl) {
        try {
            if (value == null) {
                // 缓存空值防穿透，使用短 TTL
                redisTemplate.opsForValue().set(fullKey(key), NULL_PLACEHOLDER, getNullTtl(key));
                return;
            }
            redisTemplate.opsForValue().set(fullKey(key), value, ttlWithJitter(ttl));
        } catch (Exception e) {
            logFailure("put", key, e);
        }
    }

    /**
     * 在基础 TTL 上增加 ±10% 随机抖动，防止大量 key 同时过期引发缓存雪崩。
     */
    private Duration ttlWithJitter(Duration base) {
        if (base == null || base.isZero()) {
            return base;
        }
        long baseSeconds = base.toSeconds();
        if (baseSeconds < 2) {
            return base; // 过短的 TTL 不加抖动
        }
        long jitter = (long) (baseSeconds * 0.1 * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
        return Duration.ofSeconds(baseSeconds + jitter);
    }

    /**
     * 缓存读取-回源-回填 一体化：命中返回缓存，否则执行 loader 并回填。
     * loader 异常照常抛出（业务错误不应被掩盖）。
     * loader 返回 null 时也缓存空占位符，短 TTL 防穿透。
     */
    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        T cached = get(key, type);
        if (cached != null) {
            return cached;
        }
        // 检查是否有空值占位符（穿透保护），有则视为未命中，需回源
        try {
            Object raw = redisTemplate.opsForValue().get(fullKey(key));
            if (NULL_PLACEHOLDER.equals(raw)) {
                // 空值缓存命中，回源重试
                T loaded = loader.get();
                put(key, loaded, ttl);
                return loaded;
            }
        } catch (Exception ignored) {
            // Redis 不可用，直接回源
        }
        T loaded = loader.get();
        put(key, loaded, ttl);
        return loaded;
    }

    // ==================== 字符串缓存 ====================

    public String getString(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(fullKey(key));
        } catch (Exception e) {
            logFailure("getString", key, e);
            return null;
        }
    }

    public void putString(String key, String value, Duration ttl) {
        try {
            if (value == null) {
                return;
            }
            stringRedisTemplate.opsForValue().set(fullKey(key), value, ttl);
        } catch (Exception e) {
            logFailure("putString", key, e);
        }
    }

    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(fullKey(key)));
        } catch (Exception e) {
            logFailure("exists", key, e);
            return false;
        }
    }

    // ==================== 失效 ====================

    public void evict(String key) {
        try {
            redisTemplate.delete(fullKey(key));
        } catch (Exception e) {
            logFailure("evict", key, e);
        }
    }

    /**
     * 按前缀模式批量删除（用于版本级失效，如 {@code graph:{versionId}:*}）。
     * 使用 SCAN 游标迭代，避免 KEYS 命令阻塞 Redis 主线程。
     */
    public void evictByPrefix(String prefix) {
        try {
            String pattern = fullKey(prefix) + "*";
            List<String> allKeys = new ArrayList<>();
            ScanOptions options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(1000)
                    .build();
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    allKeys.add(cursor.next());
                    // 分批删除，每 100 个 key 执行一次 DEL
                    if (allKeys.size() >= 100) {
                        redisTemplate.delete((Collection<String>) allKeys);
                        allKeys.clear();
                    }
                }
            }
            if (!allKeys.isEmpty()) {
                redisTemplate.delete((Collection<String>) allKeys);
            }
            log.debug("Cache evicted by prefix {}", prefix);
        } catch (Exception e) {
            logFailure("evictByPrefix", prefix, e);
        }
    }

    /**
     * 按多个模式批量失效（一次调用替代多次 evictByPrefix）。
     * 所有 pattern 使用 SCAN 收集 key，统一分批删除。
     */
    public void evictByPatterns(List<String> patterns) {
        try {
            List<String> allKeys = new ArrayList<>();
            for (String pattern : patterns) {
                String fullPattern = pattern.startsWith("lg:") ? pattern : fullKey(pattern);
                ScanOptions options = ScanOptions.scanOptions()
                        .match(fullPattern)
                        .count(1000)
                        .build();
                try (Cursor<String> cursor = redisTemplate.scan(options)) {
                    while (cursor.hasNext()) {
                        allKeys.add(cursor.next());
                    }
                }
            }
            if (!allKeys.isEmpty()) {
                // 分批删除
                for (int i = 0; i < allKeys.size(); i += 100) {
                    int end = Math.min(i + 100, allKeys.size());
                    redisTemplate.delete((Collection<String>) allKeys.subList(i, end));
                }
                log.debug("Cache evicted {} keys by {} patterns", allKeys.size(), patterns.size());
            }
        } catch (Exception e) {
            logFailure("evictByPatterns", patterns.toString(), e);
        }
    }
}
