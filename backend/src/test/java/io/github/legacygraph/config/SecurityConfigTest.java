package io.github.legacygraph.config;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecurityConfig 集成测试。
 * <p>
 * 验证：
 * 1. {@code PasswordEncoder} Bean 正确注入（BCrypt 加密/匹配）
 * 2. {@code CorsConfigurationSource} Bean CORS 策略
 * </p>
 */
@SpringBootTest
class SecurityConfigTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    /**
     * 验证 PasswordEncoder Bean 存在且为 BCryptPasswordEncoder。
     */
    @Test
    void test_passwordEncoder_bean_injected() {
        assertThat(passwordEncoder).isNotNull();
    }

    /**
     * 验证密码加密和匹配功能正常。
     */
    @Test
    void test_bcrypt_encode_and_match() {
        String rawPassword = "MySecurePassword123!";
        String encoded = passwordEncoder.encode(rawPassword);

        // 编码后的密码应与原文不同
        assertThat(encoded).isNotBlank();
        assertThat(encoded).isNotEqualTo(rawPassword);

        // BCrypt 密文以 "$2a$" 开头
        assertThat(encoded).startsWith("$2a$");

        // 原文与编码密文匹配
        assertThat(passwordEncoder.matches(rawPassword, encoded)).isTrue();

        // 不同原文不应匹配
        assertThat(passwordEncoder.matches("WrongPassword", encoded)).isFalse();
    }

    /**
     * 验证同一次 BCrypt 编码每次生成不同的 salt（密文不同）。
     */
    @Test
    void test_bcrypt_salt_unique() {
        String raw = "samePassword";
        String enc1 = passwordEncoder.encode(raw);
        String enc2 = passwordEncoder.encode(raw);

        assertThat(enc1).isNotEqualTo(enc2);
        assertThat(passwordEncoder.matches(raw, enc1)).isTrue();
        assertThat(passwordEncoder.matches(raw, enc2)).isTrue();
    }

    /**
     * 验证 CORS 配置：允许所有来源、常见 HTTP 方法、允许凭据。
     */
    @Test
    void test_corsConfiguration() {
        assertThat(corsConfigurationSource).isNotNull();

        // 使用 MockHttpServletRequest 避免 NPE
        jakarta.servlet.http.HttpServletRequest mockRequest =
                new org.springframework.mock.web.MockHttpServletRequest();
        CorsConfiguration config = corsConfigurationSource.getCorsConfiguration(mockRequest);
        assertThat(config).isNotNull();

        // 允许所有来源模式
        assertThat(config.getAllowedOriginPatterns()).contains("*");

        // 允许常见 HTTP 方法
        assertThat(config.getAllowedMethods()).contains("GET", "POST", "PUT", "DELETE", "OPTIONS");

        // 允许凭据
        assertThat(config.getAllowCredentials()).isTrue();

        // 预检缓存时间
        assertThat(config.getMaxAge()).isEqualTo(3600L);
    }
}
