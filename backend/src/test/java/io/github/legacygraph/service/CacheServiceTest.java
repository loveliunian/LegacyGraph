package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.system.CacheService;

/**
 * CacheService 测试 — 验证读写、getOrLoad 回源回填、以及 Redis 故障时的容错降级。
 */
@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOps;
    @Mock
    private ValueOperations<String, String> stringOps;

    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new CacheService(redisTemplate, stringRedisTemplate, new ObjectMapper());
    }

    @Test
    void testPutAndGet_Object() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("lg:test:key")).thenReturn("hello");

        cacheService.put("test:key", "hello", Duration.ofMinutes(1));
        String result = cacheService.get("test:key", String.class);
        assertEquals("hello", result);
        // TTL 带 ±10% 抖动，使用 any() 匹配
        verify(valueOps).set(eq("lg:test:key"), eq("hello"), any(Duration.class));
    }

    @Test
    void testGetOrLoad_MissThenLoadAndBackfill() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("lg:exp:key")).thenReturn(null);

        String result = cacheService.getOrLoad("exp:key", String.class, Duration.ofSeconds(10),
                () -> "computed");

        assertEquals("computed", result);
        verify(valueOps).set(eq("lg:exp:key"), eq("computed"), eq(Duration.ofSeconds(10)));
    }

    @Test
    void testGetOrLoad_HitSkipsLoader() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("lg:hit:key")).thenReturn("cached");

        String result = cacheService.getOrLoad("hit:key", String.class, Duration.ofSeconds(10),
                () -> { fail("loader should not run on cache hit"); return "x"; });

        assertEquals("cached", result);
        verify(valueOps, never()).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void testPutNullValue_CachesPlaceholder() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cacheService.put("k", null, Duration.ofMinutes(1));
        // null 值应缓存 __NULL__ 占位符（防穿透），使用短 TTL
        verify(valueOps).set(eq("lg:k"), eq("__NULL__"), any(Duration.class));
    }

    @Test
    void testGetStringAndExists() {
        when(stringRedisTemplate.opsForValue()).thenReturn(stringOps);
        when(stringOps.get("lg:s:k")).thenReturn("v");
        when(stringRedisTemplate.hasKey("lg:s:k")).thenReturn(true);

        assertEquals("v", cacheService.getString("s:k"));
        assertTrue(cacheService.exists("s:k"));
    }

    @Test
    void testEvictByPrefix_UsesScanAndDelete() {
        // SCAN 替代 KEYS：游标迭代 + 分批删除
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.Cursor<String> cursor = mock(org.springframework.data.redis.core.Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("lg:graph:v1:api", "lg:graph:v1:table");
        when(redisTemplate.scan(any(org.springframework.data.redis.core.ScanOptions.class))).thenReturn(cursor);

        cacheService.evictByPrefix("graph:v1:");
        verify(redisTemplate).scan(any(org.springframework.data.redis.core.ScanOptions.class));
        // 验证分批删除被调用（使用 anyCollectionOf 避免重载歧义）
        verify(redisTemplate, atLeastOnce()).delete(anyCollection());
    }

    // ==================== 容错降级 ====================

    @Test
    void testGetFailure_ReturnsNull_NotThrows() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        assertNull(cacheService.get("k", String.class));
    }

    @Test
    void testPutFailure_DoesNotThrow() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        assertDoesNotThrow(() -> cacheService.put("k", "v", Duration.ofMinutes(1)));
    }

    @Test
    void testExistsFailure_TreatedAsAbsent() {
        when(stringRedisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        assertFalse(cacheService.exists("k"));
    }

    @Test
    void testEvictFailure_DoesNotThrow() {
        when(redisTemplate.delete((String) any())).thenThrow(new RuntimeException("redis down"));
        assertDoesNotThrow(() -> cacheService.evict("k"));
    }
}
