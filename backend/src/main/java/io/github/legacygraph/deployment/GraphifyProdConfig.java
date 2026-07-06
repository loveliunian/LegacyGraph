package io.github.legacygraph.deployment;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Graphify 生产环境配置
 * <p>
 * 关键配置项：
 * <ul>
 *   <li>graphify.enabled: 是否启用 Graphify 集成</li>
 *   <li>graphify.auto-import-on-deploy: 部署时是否自动触发导入</li>
 *   <li>graphify.review-mode: 审核模式 (strict/enhanced/standard)</li>
 *   <li>graphify.max-import-size-mb: 最大导入文件大小(MB)</li>
 *   <li>graphify.retry-max-attempts: 重试最大次数</li>
 * </ul>
 */
@Configuration
@Profile("prod")
public class GraphifyProdConfig {
    
    // 生产环境默认配置
    public static final boolean DEFAULT_ENABLED = false;
    public static final boolean DEFAULT_AUTO_IMPORT = false;  // 生产环境默认不自动导入
    public static final String DEFAULT_REVIEW_MODE = "strict";  // 生产环境使用严格审核
    public static final int DEFAULT_MAX_IMPORT_SIZE_MB = 100;
    public static final int DEFAULT_RETRY_MAX_ATTEMPTS = 3;
    
    // 性能调优
    public static final int DEFAULT_BATCH_SIZE = 500;  // 批量处理大小
    public static final int DEFAULT_PARALLEL_THREADS = 4;  // 并行线程数
    public static final long DEFAULT_TIMEOUT_SECONDS = 3600;  // 超时时间（1小时）
    
    // 监控和告警
    public static final boolean DEFAULT_METRICS_ENABLED = true;
    public static final boolean DEFAULT_HEALTH_CHECK_ENABLED = true;
    public static final long DEFAULT_HEALTH_CHECK_INTERVAL_SECONDS = 300;  // 5分钟
}
