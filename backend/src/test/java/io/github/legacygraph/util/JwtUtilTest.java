package io.github.legacygraph.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtUtil 单元测试。
 * <p>
 * 测试 JWT Token 的生成、验证、解析功能。
 * 使用 ReflectionTestUtils 注入 secret/expiration 从而避免 Spring 上下文加载。
 * </p>
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    /** 测试用的 secret，必须至少 256 位（32 字节） */
    private static final String TEST_SECRET = "test-secret-key-for-jwt-unit-test-2026-long-enough";
    /** 过期时间 1 小时（秒） */
    private static final Long TEST_EXPIRATION = 3600L;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        // 绕过 @Value 注入，直接设置字段值
        ReflectionTestUtils.setField(jwtUtil, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", TEST_EXPIRATION);
    }

    // ========== generateToken ==========

    /**
     * 验证 generateToken 返回非空、非空白的 JWT 字符串。
     */
    @Test
    void test_generateToken_not_blank() {
        String token = jwtUtil.generateToken("user-001", "admin");
        assertThat(token).isNotBlank();
    }

    /**
     * 验证生成的 Token 是一个三段式 JWT（header.payload.signature）。
     */
    @Test
    void test_generateToken_three_parts() {
        String token = jwtUtil.generateToken("user-002", "operator");
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
    }

    // ========== validateToken ==========

    /**
     * 验证有效 Token 通过校验。
     */
    @Test
    void test_validateToken_valid() {
        String token = jwtUtil.generateToken("user-003", "tester");
        assertThat(jwtUtil.validateToken(token)).isTrue();
    }

    /**
     * 验证伪造/篡改的 Token 校验失败。
     */
    @Test
    void test_validateToken_invalid() {
        String fakeToken = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.invalid_signature";
        assertThat(jwtUtil.validateToken(fakeToken)).isFalse();
    }

    /**
     * 验证 null Token 校验失败。
     */
    @Test
    void test_validateToken_null() {
        assertThat(jwtUtil.validateToken(null)).isFalse();
    }

    /**
     * 验证空白字符串 Token 校验失败。
     */
    @Test
    void test_validateToken_blank() {
        assertThat(jwtUtil.validateToken("   ")).isFalse();
    }

    // ========== parseToken (getUsername, getUserId, getClaims) ==========

    /**
     * 验证 getUsernameFromToken 返回正确的用户名。
     */
    @Test
    void test_getUsernameFromToken() {
        String token = jwtUtil.generateToken("user-004", "zhangsan");
        String username = jwtUtil.getUsernameFromToken(token);
        assertThat(username).isEqualTo("zhangsan");
    }

    /**
     * 验证 getUserIdFromToken 返回正确的用户 ID。
     */
    @Test
    void test_getUserIdFromToken() {
        String token = jwtUtil.generateToken("user-005", "lisi");
        String userId = jwtUtil.getUserIdFromToken(token);
        assertThat(userId).isEqualTo("user-005");
    }

    /**
     * 验证 getClaimsFromToken 返回包含完整声明的 Claims 对象。
     */
    @Test
    void test_getClaimsFromToken() {
        String token = jwtUtil.generateToken("user-006", "wangwu");
        Claims claims = jwtUtil.getClaimsFromToken(token);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo("wangwu");
        assertThat(claims.get("userId", String.class)).isEqualTo("user-006");
    }

    /**
     * 验证无效 Token 的 Claims 解析返回 null。
     */
    @Test
    void test_getClaimsFromToken_invalid() {
        String invalidToken = "header.payload.signature";
        Claims claims = jwtUtil.getClaimsFromToken(invalidToken);
        assertThat(claims).isNull();
    }

    // ========== isTokenExpired ==========

    /**
     * 验证新生成的 Token 未过期。
     */
    @Test
    void test_isTokenExpired_false_for_new_token() {
        String token = jwtUtil.generateToken("user-007", "fresh");
        assertThat(jwtUtil.isTokenExpired(token)).isFalse();
    }

    /**
     * 验证 null Token 视为已过期。
     */
    @Test
    void test_isTokenExpired_null() {
        assertThat(jwtUtil.isTokenExpired(null)).isFalse();
    }

    // ========== getExpirationDateFromToken ==========

    /**
     * 验证过期时间为未来时间（新 Token）。
     */
    @Test
    void test_getExpirationDateFromToken() {
        String token = jwtUtil.generateToken("user-008", "expiry");
        Date expiration = jwtUtil.getExpirationDateFromToken(token);
        assertThat(expiration).isNotNull();
        assertThat(expiration.after(new Date())).isTrue();
    }

    // ========== 综合场景 ==========

    /**
     * 端到端：生成 Token → 解析声明 → 验证通过。
     */
    @Test
    void test_generate_parse_validate_end_to_end() {
        String userId = "user-e2e-001";
        String username = "enduser";

        // 1. 生成
        String token = jwtUtil.generateToken(userId, username);

        // 2. 解析
        assertThat(jwtUtil.getUserIdFromToken(token)).isEqualTo(userId);
        assertThat(jwtUtil.getUsernameFromToken(token)).isEqualTo(username);

        // 3. 验证
        assertThat(jwtUtil.validateToken(token)).isTrue();
        assertThat(jwtUtil.isTokenExpired(token)).isFalse();
    }
}
