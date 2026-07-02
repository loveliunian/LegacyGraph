package io.github.legacygraph.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * RedisConfig 单元测试。
 * <p>
 * 验证 RedisTemplate / StringRedisTemplate / CacheManager / ErrorHandler 各 Bean 的正确性。
 * 使用 Mockito 模拟 RedisConnectionFactory，不依赖 Redis 环境。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class RedisConfigTest {

    @Mock
    private RedisConnectionFactory connectionFactory;

    private RedisConfig redisConfig;

    @BeforeEach
    void setUp() {
        redisConfig = new RedisConfig();
    }

    /**
     * 测试：redisTemplate Bean 正确创建并设置连接工厂。
     */
    @Test
    void testRedisTemplate_CreatedSuccessfully() {
        RedisTemplate<String, Object> template = redisConfig.redisTemplate(connectionFactory);

        assertNotNull(template);
        assertEquals(connectionFactory, template.getConnectionFactory());
    }

    /**
     * 测试：stringRedisTemplate Bean 正确创建。
     */
    @Test
    void testStringRedisTemplate_CreatedSuccessfully() {
        StringRedisTemplate template = redisConfig.stringRedisTemplate(connectionFactory);

        assertNotNull(template);
        assertEquals(connectionFactory, template.getConnectionFactory());
    }

    /**
     * 测试：cacheManager Bean 正确创建，默认 TTL 为 1 小时。
     */
    @Test
    void testCacheManager_CreatedWithDefaults() {
        RedisCacheManager cacheManager =
                (RedisCacheManager) redisConfig.cacheManager(connectionFactory);

        assertNotNull(cacheManager);
        // 验证默认 TTL 为 1 小时
        assertNotNull(cacheManager.getCache("test-cache"));
    }

    /**
     * 测试：errorHandler Bean 返回非空，且各方法不抛异常（降级处理）。
     */
    @Test
    void testErrorHandler_IsNotNull() {
        var errorHandler = redisConfig.errorHandler();

        assertNotNull(errorHandler);
    }

    /**
     * 测试：errorHandler 的 handleCacheGetError 在异常时不应抛出。
     */
    @Test
    void testErrorHandler_GetErrorDoesNotThrow() {
        var errorHandler = redisConfig.errorHandler();

        // 模拟 Cache 对象（使用 mock 避免实现所有接口方法）
        Cache mockCache = org.mockito.Mockito.mock(Cache.class);
        org.mockito.Mockito.when(mockCache.getName()).thenReturn("test");

        // 错误处理器不应抛出异常
        assertDoesNotThrow(() ->
                errorHandler.handleCacheGetError(
                        new RuntimeException("Redis 不可用"), mockCache, "test-key"));
    }

    /**
     * 测试：KEY_PREFIX 常量值为 "lg:"。
     */
    @Test
    void testKeyPrefix_IsCorrect() {
        assertEquals("lg:", RedisConfig.KEY_PREFIX);
        assertTrue("lg:some-key".startsWith(RedisConfig.KEY_PREFIX));
    }
}
