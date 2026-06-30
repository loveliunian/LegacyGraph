package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
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

    public CacheService(RedisTemplate<String, Object> redisTemplate,
                        StringRedisTemplate stringRedisTemplate,
                        ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
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
            if (type.isInstance(raw)) {
                return type.cast(raw);
            }
            // 经由 JSON 多态可能已是目标类型；否则做一次转换兜底
            return objectMapper.convertValue(raw, type);
        } catch (Exception e) {
            log.warn("Cache get failed (degrade), key={}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 写入对象并设置 TTL；异常忽略（不阻断业务）。
     */
    public void put(String key, Object value, Duration ttl) {
        try {
            if (value == null) {
                return;
            }
            redisTemplate.opsForValue().set(fullKey(key), value, ttl);
        } catch (Exception e) {
            log.warn("Cache put failed (ignored), key={}: {}", key, e.getMessage());
        }
    }

    /**
     * 缓存读取-回源-回填 一体化：命中返回缓存，否则执行 loader 并回填。
     * loader 异常照常抛出（业务错误不应被掩盖）。
     */
    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        T cached = get(key, type);
        if (cached != null) {
            return cached;
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
            log.warn("Cache getString failed (degrade), key={}: {}", key, e.getMessage());
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
            log.warn("Cache putString failed (ignored), key={}: {}", key, e.getMessage());
        }
    }

    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(fullKey(key)));
        } catch (Exception e) {
            log.warn("Cache exists failed (treat as absent), key={}: {}", key, e.getMessage());
            return false;
        }
    }

    // ==================== 失效 ====================

    public void evict(String key) {
        try {
            redisTemplate.delete(fullKey(key));
        } catch (Exception e) {
            log.warn("Cache evict failed (ignored), key={}: {}", key, e.getMessage());
        }
    }

    /**
     * 按前缀模式批量删除（用于版本级失效，如 {@code graph:{versionId}:*}）。
     * 使用 SCAN 避免阻塞 Redis。
     */
    public void evictByPrefix(String prefix) {
        try {
            Set<String> keys = redisTemplate.keys(fullKey(prefix) + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("Cache evicted {} keys by prefix {}", keys.size(), prefix);
            }
        } catch (Exception e) {
            log.warn("Cache evictByPrefix failed (ignored), prefix={}: {}", prefix, e.getMessage());
        }
    }
}
