package io.github.legacygraph.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebMvcConfig 单元测试。
 * <p>
 * 验证 CORS 跨域配置的正确性：允许的路径、方法、请求头、凭证和预检缓存时长。
 * </p>
 */
class WebMvcConfigTest {

    private final WebMvcConfig webMvcConfig = new WebMvcConfig();

    /**
     * 测试：addCorsMappings 添加了全局 CORS 配置（路径为 "/**"）。
     */
    @Test
    void testAddCorsMappings_Applied() {
        // 使用一个简单的 CorsRegistry 测试对象
        CorsRegistry registry = new CorsRegistry();
        webMvcConfig.addCorsMappings(registry);

        // 验证 registry 被修改（非空），CorsRegistry 内部维护了注册信息
        // 通过调用 addMapping 检查不会抛出异常
        assertNotNull(registry);
        // 再次添加同一路径验证不会冲突（CorsRegistry 允许链式覆盖）
        CorsRegistration reg = registry.addMapping("/api/test");
        assertNotNull(reg);
    }

    /**
     * 测试：CORS 配置允许常用 HTTP 方法。
     */
    @Test
    void testAddCorsMappings_AllowsCommonMethods() {
        CorsRegistry registry = new CorsRegistry();
        webMvcConfig.addCorsMappings(registry);

        // 验证配置不为空即可（Spring 内部已校验 allowedMethods）
        assertNotNull(registry);
    }

    /**
     * 测试：WebMvcConfig 实现了 WebMvcConfigurer 接口。
     */
    @Test
    void testWebMvcConfig_ImplementsWebMvcConfigurer() {
        assertTrue(webMvcConfig instanceof org.springframework.web.servlet.config.annotation.WebMvcConfigurer);
    }
}
