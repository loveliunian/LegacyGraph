package io.github.legacygraph.service;

import io.github.legacygraph.service.system.TokenBlacklistService;
import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.util.JwtUtil;
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

@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    @Mock
    private CacheService cacheService;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private TokenBlacklistService tokenBlacklistService;

    private static final String VALID_TOKEN = "eyJhbGciOiJIUzI1NiJ9.valid-token";
    private static final long FUTURE_EXPIRY = System.currentTimeMillis() + 3600_000L;

    @Test
    void testBlacklist_ValidToken() {
        when(jwtUtil.getExpirationDateFromToken(VALID_TOKEN))
                .thenReturn(new Date(FUTURE_EXPIRY));

        tokenBlacklistService.blacklist(VALID_TOKEN);

        verify(cacheService).putString(
                contains("auth:blacklist:"),
                eq("1"),
                argThat(ttl -> ttl.toMillis() > 0));
    }

    @Test
    void testIsBlacklisted_True() {
        when(cacheService.exists(contains("auth:blacklist:"))).thenReturn(true);

        assertTrue(tokenBlacklistService.isBlacklisted(VALID_TOKEN));
        verify(cacheService).exists(contains("auth:blacklist:"));
    }

    @Test
    void testIsBlacklisted_False() {
        when(cacheService.exists(contains("auth:blacklist:"))).thenReturn(false);

        assertFalse(tokenBlacklistService.isBlacklisted(VALID_TOKEN));
    }

    @Test
    void testBlacklist_NullToken() {
        tokenBlacklistService.blacklist(null);
        verify(cacheService, never()).putString(anyString(), anyString(), any());
    }

    @Test
    void testIsBlacklisted_BlankToken() {
        assertFalse(tokenBlacklistService.isBlacklisted("   "));
        verify(cacheService, never()).exists(anyString());
    }

    @Test
    void testBlacklist_ExpiredToken() {
        String expiredToken = "expired-token";
        when(jwtUtil.getExpirationDateFromToken(expiredToken))
                .thenReturn(new Date(System.currentTimeMillis() - 1000));

        tokenBlacklistService.blacklist(expiredToken);

        verify(cacheService, never()).putString(anyString(), anyString(), any());
    }
}
