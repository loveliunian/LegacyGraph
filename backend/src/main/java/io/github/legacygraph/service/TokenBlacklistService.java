package io.github.legacygraph.service;

import io.github.legacygraph.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Date;

/**
 * JWT 登出黑名单服务（场景 2）。
 *
 * <p>JWT 本身无状态，登出后旧 token 在过期前仍有效。本服务在登出时把 token 写入 Redis 黑名单，
 * TTL = token 剩余有效期；鉴权过滤器校验时检查黑名单，实现"真正登出"。</p>
 *
 * <p>Redis 不可用时降级：写入忽略、校验视为未拉黑（不阻断正常登录用户）。</p>
 */
@Slf4j
@Service
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "auth:blacklist:";

    private final CacheService cacheService;
    private final JwtUtil jwtUtil;

    public TokenBlacklistService(CacheService cacheService, JwtUtil jwtUtil) {
        this.cacheService = cacheService;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 将 token 加入黑名单，TTL 取其剩余有效期。
     */
    public void blacklist(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            Date expiration = jwtUtil.getExpirationDateFromToken(token);
            long ttlMs = expiration != null
                    ? expiration.getTime() - System.currentTimeMillis()
                    : 0L;
            if (ttlMs <= 0) {
                // 已过期的 token 无需拉黑
                return;
            }
            cacheService.putString(key(token), "1", Duration.ofMillis(ttlMs));
            log.debug("Token blacklisted, ttlMs={}", ttlMs);
        } catch (Exception e) {
            log.warn("Failed to blacklist token (ignored): {}", e.getMessage());
        }
    }

    /**
     * 判断 token 是否已被拉黑。Redis 不可用时返回 false（降级）。
     */
    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return cacheService.exists(key(token));
    }

    private String key(String token) {
        return KEY_PREFIX + sha256(token);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // 退化为原文（极端情况），保证功能可用
            return input;
        }
    }
}
