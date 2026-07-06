package io.github.legacygraph.service.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.LoginResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证会话服务 — 将用户登录信息和 Token 存储到 Redis。
 *
 * <p>Redis Key 设计：
 * <ul>
 *   <li>{@code auth:token:{tokenHash}} → 用户ID（用于快速校验 token 是否属于有效会话）</li>
 *   <li>{@code auth:session:{userId}} → 用户会话 JSON（含 token、用户信息、登录时间）</li>
 *   <li>{@code auth:user-tokens:{userId}} → 该用户当前所有有效 token 的 hash 列表（用于支持踢人/多端管理）</li>
 * </ul>
 *
 * <p>Redis 不可用时降级：写入忽略、读取返回 null，不阻断业务。</p>
 */
@Slf4j
@Service
public class AuthSessionService {

    private static final String TOKEN_PREFIX = "auth:token:";
    private static final String SESSION_PREFIX = "auth:session:";
    private static final String USER_TOKENS_PREFIX = "auth:user-tokens:";

    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    /** 本地缓存兜底：Redis 不可用时保持基本功能 */
    private final Map<String, SessionData> localSessionCache = new ConcurrentHashMap<>();

    public AuthSessionService(CacheService cacheService, ObjectMapper objectMapper) {
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    /**
     * 登录会话数据
     */
    public record SessionData(
            String userId,
            String username,
            String accessToken,
            String refreshToken,
            LoginResponse.UserInfo userInfo,
            long loginTime,
            long expireTime
    ) {}

    /**
     * 保存登录会话到 Redis。
     *
     * @param accessToken  访问令牌
     * @param refreshToken 刷新令牌
     * @param userInfo     用户信息
     * @param ttl          会话有效期
     */
    public void saveSession(String accessToken, String refreshToken,
                            LoginResponse.UserInfo userInfo, Duration ttl) {
        if (userInfo == null || userInfo.getId() == null) {
            return;
        }
        try {
            String userId = userInfo.getId();
            long now = System.currentTimeMillis();
            long expireAt = now + ttl.toMillis();

            SessionData session = new SessionData(
                    userId, userInfo.getUsername(),
                    accessToken, refreshToken,
                    userInfo, now, expireAt
            );

            String sessionJson = objectMapper.writeValueAsString(session);

            // 1. token → userId 映射（快速校验）
            cacheService.putString(TOKEN_PREFIX + tokenHash(accessToken), userId, ttl);
            if (refreshToken != null) {
                cacheService.putString(TOKEN_PREFIX + tokenHash(refreshToken), userId, ttl);
            }

            // 2. userId → session JSON
            cacheService.putString(SESSION_PREFIX + userId, sessionJson, ttl);

            // 3. 记录用户的 token 列表（用于多端管理）
            cacheService.putString(
                    USER_TOKENS_PREFIX + userId,
                    tokenHash(accessToken),
                    ttl
            );

            // 本地缓存兜底
            localSessionCache.put(userId, session);

            log.debug("Session saved for user={}, ttl={}s", userId, ttl.getSeconds());
        } catch (Exception e) {
            log.warn("Failed to save session (ignored): {}", e.getMessage());
        }
    }

    /**
     * 根据 token 获取用户 ID。
     *
     * @param token JWT token
     * @return 用户ID，未找到返回 null
     */
    public String getUserIdByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return cacheService.getString(TOKEN_PREFIX + tokenHash(token));
        } catch (Exception e) {
            log.warn("Failed to get userId by token (degrade): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取用户会话信息。
     *
     * @param userId 用户ID
     * @return 会话数据，未找到返回 null
     */
    public SessionData getSession(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            String json = cacheService.getString(SESSION_PREFIX + userId);
            if (json != null) {
                return objectMapper.readValue(json, SessionData.class);
            }
        } catch (Exception e) {
            log.warn("Failed to get session (degrade), userId={}: {}", userId, e.getMessage());
        }
        // 降级到本地缓存
        return localSessionCache.get(userId);
    }

    /**
     * 移除用户会话（登出时调用）。
     *
     * @param token JWT token
     */
    public void removeSession(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            String userId = getUserIdByToken(token);
            if (userId != null) {
                // 删除 token → userId 映射
                cacheService.evict(TOKEN_PREFIX + tokenHash(token));
                // 删除用户会话
                cacheService.evict(SESSION_PREFIX + userId);
                // 删除用户 token 列表
                cacheService.evict(USER_TOKENS_PREFIX + userId);
                // 清除本地缓存
                localSessionCache.remove(userId);
                log.debug("Session removed for userId={}", userId);
            }
        } catch (Exception e) {
            log.warn("Failed to remove session (ignored): {}", e.getMessage());
        }
    }

    /**
     * 检查 token 是否属于有效会话（存在于 Redis 中）。
     *
     * @param token JWT token
     * @return 是否存在有效会话
     */
    public boolean hasValidSession(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            return cacheService.exists(TOKEN_PREFIX + tokenHash(token));
        } catch (Exception e) {
            log.warn("Failed to check session (degrade to true): {}", e.getMessage());
            // Redis 不可用时降级：只要 JWT 本身有效就放行
            return true;
        }
    }

    /**
     * 获取当前在线用户数量。
     */
    public int getOnlineUserCount() {
        return localSessionCache.size();
    }

    /**
     * 获取所有在线用户 ID 列表。
     */
    public List<String> getOnlineUserIds() {
        return new ArrayList<>(localSessionCache.keySet());
    }

    private String tokenHash(String token) {
        // 取 token 前 16 位作为简短标识，避免 key 过长
        if (token.length() <= 16) {
            return token;
        }
        return token.substring(0, 16) + ":" + Integer.toHexString(token.hashCode());
    }
}
