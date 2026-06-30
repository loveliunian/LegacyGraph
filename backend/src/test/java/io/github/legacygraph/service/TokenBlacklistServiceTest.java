package io.github.legacygraph.service;

import io.github.legacygraph.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TokenBlacklistService 测试 — 登出黑名单写入与校验、过期 token 跳过、Redis 故障降级。
 */
@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    @Mock
    private CacheService cacheService;
    @Mock
    private JwtUtil jwtUtil;

    @Test
    void testBlacklist_WritesWithRemainingTtl() {
        String token = "abc.def.ghi";
        long future = System.currentTimeMillis() + 600_000L;
        when(jwtUtil.getExpirationDateFromToken(token)).thenReturn(new Date(future));

        new TokenBlacklistService(cacheService, jwtUtil).blacklist(token);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(cacheService).putString(startsWith("auth:blacklist:"), eq("1"), ttlCaptor.capture());
        assertTrue(ttlCaptor.getValue().toMillis() > 0 && ttlCaptor.getValue().toMillis() <= 600_000L);
    }

    @Test
    void testBlacklist_ExpiredToken_Noop() {
        String token = "expired";
        when(jwtUtil.getExpirationDateFromToken(token)).thenReturn(new Date(System.currentTimeMillis() - 1000));
        new TokenBlacklistService(cacheService, jwtUtil).blacklist(token);
        verifyNoInteractions(cacheService);
    }

    @Test
    void testBlacklist_NullOrBlank_Noop() {
        new TokenBlacklistService(cacheService, jwtUtil).blacklist(null);
        new TokenBlacklistService(cacheService, jwtUtil).blacklist("  ");
        verifyNoInteractions(cacheService);
    }

    @Test
    void testIsBlacklisted_DelegatesToCacheExists() {
        when(cacheService.exists(startsWith("auth:blacklist:"))).thenReturn(true);
        assertTrue(new TokenBlacklistService(cacheService, jwtUtil).isBlacklisted("some-token"));
    }

    @Test
    void testIsBlacklisted_NullToken_ReturnsFalse() {
        assertFalse(new TokenBlacklistService(cacheService, jwtUtil).isBlacklisted(null));
        verifyNoInteractions(cacheService);
    }

    @Test
    void testBlacklist_JwtUtilThrows_DegradesSilently() {
        when(jwtUtil.getExpirationDateFromToken(any())).thenThrow(new RuntimeException("parse error"));
        assertDoesNotThrow(() -> new TokenBlacklistService(cacheService, jwtUtil).blacklist("t"));
    }
}
