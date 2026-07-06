package io.github.legacygraph.deployment;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Graphify 健康检查指标
 */
@Component
public class GraphifyHealthIndicator implements HealthIndicator {
    
    private volatile boolean importInProgress = false;
    private volatile String lastImportStatus = "NEVER_RUN";
    private volatile long lastImportTime = 0;
    private volatile int pendingReviewCount = 0;
    
    @Override
    public Health health() {
        Health.Builder builder = new Health.Builder();
        
        builder.withDetail("importInProgress", importInProgress);
        builder.withDetail("lastImportStatus", lastImportStatus);
        builder.withDetail("lastImportTime", lastImportTime);
        builder.withDetail("pendingReviewCount", pendingReviewCount);
        
        // 如果有导入正在进行，状态为 UP（正常）
        // 如果最后一次导入失败且没有在进行中的导入，状态为 DOWN
        if (!importInProgress && "FAILED".equals(lastImportStatus)) {
            builder.down();
            builder.withDetail("reason", "Last import failed");
        } else {
            builder.up();
        }
        
        return builder.build();
    }
    
    // 更新状态的方法
    public void setImportInProgress(boolean inProgress) {
        this.importInProgress = inProgress;
    }
    
    public void updateLastImportStatus(String status) {
        this.lastImportStatus = status;
        this.lastImportTime = System.currentTimeMillis();
    }
    
    public void setPendingReviewCount(int count) {
        this.pendingReviewCount = count;
    }
    
    public Map<String, Object> getMetrics() {
        return Map.of(
            "importInProgress", importInProgress,
            "lastImportStatus", lastImportStatus,
            "lastImportTime", lastImportTime,
            "pendingReviewCount", pendingReviewCount
        );
    }
}
