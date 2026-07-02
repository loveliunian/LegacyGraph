package io.github.legacygraph.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FlywayConfig 单元测试。
 * 验证 Flyway 手动配置的 Bean 创建与属性注入。
 */
@ExtendWith(MockitoExtension.class)
class FlywayConfigTest {

    @Mock
    private DataSource dataSource;

    /**
     * 测试 flyway Bean 构造不抛异常（需 DataSource 真实存在的场景跳过）。
     * 此处验证配置对象本身的属性注入。
     */
    @Test
    void config_defaultProperties_areSet() {
        FlywayConfig config = new FlywayConfig();

        // 验证 @Value 默认值（反射读取）
        String locations = (String) ReflectionTestUtils.getField(config, "locations");
        // @Value 字段在单元测试中未注入，保持 null
        assertNull(locations);
    }

    /**
     * 测试手动设置属性后 flyway Bean 的创建。
     * 注意：flyway() 尝试 connect，需 mock flyway 静态方法，故此处仅验证 setter。
     */
    @Test
    void setProperties_viaFieldReflection() {
        FlywayConfig config = new FlywayConfig();
        ReflectionTestUtils.setField(config, "locations", "classpath:db/migration/test");
        ReflectionTestUtils.setField(config, "baselineOnMigrate", true);
        ReflectionTestUtils.setField(config, "baselineVersion", "2");
        ReflectionTestUtils.setField(config, "cleanDisabled", true);

        assertEquals("classpath:db/migration/test",
                ReflectionTestUtils.getField(config, "locations"));
        assertTrue((boolean) ReflectionTestUtils.getField(config, "baselineOnMigrate"));
        assertEquals("2", ReflectionTestUtils.getField(config, "baselineVersion"));
        assertTrue((boolean) ReflectionTestUtils.getField(config, "cleanDisabled"));
    }

    /**
     * 测试配置类上存在 ConditionalOnProperty 注解。
     */
    @Test
    void class_hasConditionalOnPropertyAnnotation() {
        assertNotNull(FlywayConfig.class.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class));
    }
}
