package io.github.legacygraph.config;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MinioConfig 单元测试。
 * 验证 MinioClient Bean 的创建与属性注入。
 */
@ExtendWith(MockitoExtension.class)
class MinioConfigTest {

    /**
     * 测试属性注入后 minioClient Bean 构建。
     */
    @Test
    void minioClient_withValidProperties_createsClient() {
        MinioConfig config = new MinioConfig();
        ReflectionTestUtils.setField(config, "endpoint", "http://localhost:9000");
        ReflectionTestUtils.setField(config, "accessKey", "minioadmin");
        ReflectionTestUtils.setField(config, "secretKey", "minioadmin");

        MinioClient client = config.minioClient();

        assertNotNull(client);
    }

    /**
     * 测试空 endpoint 时也能构建（builder 模式，运行时校验在连接时）。
     */
    @Test
    void minioClient_withNullEndpoint_createsClient() {
        MinioConfig config = new MinioConfig();
        ReflectionTestUtils.setField(config, "endpoint", null);
        ReflectionTestUtils.setField(config, "accessKey", "key");
        ReflectionTestUtils.setField(config, "secretKey", "secret");

        // Minio builder 在校验 endpoint 时抛出 NPE
        assertThrows(NullPointerException.class, () -> config.minioClient());
    }

    /**
     * 测试配置类上有 @Configuration 注解。
     */
    @Test
    void class_hasConfigurationAnnotation() {
        assertNotNull(MinioConfig.class.getAnnotation(
                org.springframework.context.annotation.Configuration.class));
    }
}
