package io.github.legacygraph.config.health;

import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * MinIO 对象存储健康检查指示器
 * 
 * 检测项：
 * 1. MinIO 服务连接可用性
 * 2. 目标 Bucket 存在性
 * 3. Bucket 可访问性（列出对象）
 */
@Slf4j
@Component
@ConditionalOnBean(MinioClient.class)
@RequiredArgsConstructor
public class MinioHealthIndicator implements HealthIndicator {

    private final MinioClient minioClient;

    @Value("${minio.bucket-name:legacy-graph}")
    private String bucketName;

    @Override
    public Health health() {
        try {
            // 检查 Bucket 是否存在
            boolean bucketExists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build()
            );

            if (!bucketExists) {
                return Health.down()
                        .withDetail("storage", "MinIO")
                        .withDetail("bucket", bucketName)
                        .withDetail("error", "Bucket does not exist")
                        .build();
            }

            // 测试 Bucket 可访问性（列出最多 1 个对象）
            var objects = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .maxKeys(1)
                            .build()
            );
            
            // 消费结果以验证连接
            objects.forEach(obj -> {});

            return Health.up()
                    .withDetail("storage", "MinIO")
                    .withDetail("bucket", bucketName)
                    .withDetail("status", "accessible")
                    .build();

        } catch (Exception e) {
            log.error("MinIO health check failed", e);
            return Health.down()
                    .withDetail("storage", "MinIO")
                    .withDetail("bucket", bucketName)
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
