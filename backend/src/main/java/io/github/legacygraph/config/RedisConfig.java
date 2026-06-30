package io.github.legacygraph.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.cache.Cache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * Redis 基础设施配置。
 *
 * <p>统一约定：
 * <ul>
 *   <li>所有 key 以 {@code lg:} 为前缀，分号分层（如 {@code lg:config:...}）。</li>
 *   <li>value 使用 JSON 序列化（{@link GenericJackson2JsonRedisSerializer}），避免 JDK 序列化兼容问题。</li>
 *   <li>开启声明式缓存 {@code @EnableCaching}，默认 TTL 1 小时。</li>
 *   <li>缓存层故障不阻断业务：{@link CacheErrorHandler} 吞掉异常并回源（降级）。</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableCaching
public class RedisConfig implements CachingConfigurer {

    /** 统一 key 前缀 */
    public static final String KEY_PREFIX = "lg:";

    /**
     * 构造支持多态反序列化的 JSON 序列化器（缓存 DTO 时保留类型信息）。
     */
    private GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = jsonSerializer();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith(KEY_PREFIX)
                .entryTtl(Duration.ofHours(1))
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(jsonSerializer()));

        java.util.Map<String, RedisCacheConfiguration> perCache = new java.util.HashMap<>();
        // 易变视图：短 TTL 兜底（写时已显式失效，TTL 仅防漏失效）
        perCache.put("project-overview", base.entryTtl(Duration.ofMinutes(1)));
        perCache.put("validation-report", base.entryTtl(Duration.ofMinutes(5)));
        // 稳定数据：长 TTL
        perCache.put("llm-provider-default", base.entryTtl(Duration.ofHours(6)));
        perCache.put("prompt-templates", base.entryTtl(Duration.ofHours(6)));
        perCache.put("config-value", base.entryTtl(Duration.ofHours(1)));
        perCache.put("config-all", base.entryTtl(Duration.ofHours(1)));
        perCache.put("dict-items", base.entryTtl(Duration.ofHours(6)));
        perCache.put("dict-map", base.entryTtl(Duration.ofHours(6)));
        // 报告：版本内稳定，写时失效
        perCache.put("report-migration-readiness", base.entryTtl(Duration.ofHours(1)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(perCache)
                .build();
    }

    /**
     * 缓存错误处理器：Redis 不可用时记录日志并降级（回源 DB），不抛出异常打断业务。
     */
    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new SimpleCacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache GET error (degrade to source), cache={}, key={}: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Cache PUT error (ignored), cache={}, key={}: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache EVICT error (ignored), cache={}, key={}: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("Cache CLEAR error (ignored), cache={}: {}",
                        cache.getName(), exception.getMessage());
            }
        };
    }
}
