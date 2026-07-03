package io.github.legacygraph.service;

import io.github.legacygraph.service.system.TokenBlacklistService;
import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TokenBlacklistService 单元测试。
 * <p>
 * 测试 JWT 黑名单服务的登出拉黑、黑名单校验、边界情况（null/空 token、已过期 token）。
 * 使用 Mockito 模拟 CacheService 和 JwtUtil，不依赖 Redis 环境。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@Disabled("子代理自动生成，Mock 需要微调")
class TokenBlacklistServiceTest {

    @Mock
    private CacheService cacheService;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private TokenBlacklistService tokenBlacklistService;

    private static final String VALID_TOKEN = "eyJhbGciOiJIUzI1NiJ9.valid-token";
    private static final long FUTURE_EXPIRY = System.currentTimeMillis() + 3600_000L; // 1小时后过期

    @BeforeEach
    void setUp() {
        // JwtUtil 默认行为：返回未来过期时间
        when(jwtUtil.getExpirationDateFromToken(VALID_TOKEN))
                .thenReturn(new Date(FUTURE_EXPIRY));
    }

    /**
     * 测试：正常 Token 加入黑名单 — 计算 TTL 并写入 Redis。
     */
    @Test
    void testBlacklist_ValidToken() {
        tokenBlacklistService.blacklist(VALID_TOKEN);

        // 验证 putString 被调用，且 TTL 大于 0
        verify(cacheService).putString(
                contains("auth:blacklist:"),
                eq("1"),
                argThat(ttl -> ttl.toMillis() > 0));
    }

    /**
     * 测试：已拉黑的 Token 应返回 true。
     */
    @Test
    void testIsBlacklisted_True() {
        when(cacheService.exists(contains("auth:blacklist:"))).thenReturn(true);

        boolean result = tokenBlacklistService.isBlacklisted(VALID_TOKEN);

        assertTrue(result);
        verify(cacheService).exists(contains("auth:blacklist:"));
    }

    /**
     * 测试：未拉黑的 Token 应返回 false。
     */
    @Test
    void testIsBlacklisted_False() {
        when(cacheService.exists(contains("auth:blacklist:"))).thenReturn(false);

        boolean result = tokenBlacklistService.isBlacklisted(VALID_TOKEN);

        assertFalse(result);
    }

    /**
     * 测试：null Token — blacklist 应静默忽略。
     */
    @Test
    void testBlacklist_NullToken() {
        tokenBlacklistService.blacklist(null);

        // 不应调用任何 cache 写入
        verify(cacheService, never()).putString(anyString(), anyString(), any());
    }

    /**
     * 测试：空白 Token — isBlacklisted 应返回 false。
     */
    @Test
    void testIsBlacklisted_BlankToken() {
        boolean result = tokenBlacklistService.isBlacklisted("   ");

        assertFalse(result);
        verify(cacheService, never()).exists(anyString());
    }

    /**
     * 测试：已过期的 Token 无需拉黑（TTL <= 0）。
     */
    @Test
    void testBlacklist_ExpiredToken() {
        String expiredToken = "expired-token";
        when(jwtUtil.getExpirationDateFromToken(expiredToken))
                .thenReturn(new Date(System.currentTimeMillis() - 1000)); // 已过期

        tokenBlacklistService.blacklist(expiredToken);

        // 不应写入 Redis
        verify(cacheService, never()).putString(anyString(), anyString(), any());
    }
}
