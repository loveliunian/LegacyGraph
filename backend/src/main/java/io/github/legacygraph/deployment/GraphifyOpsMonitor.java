package io.github.legacygraph.deployment;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Graphify 运营监控服务
 * <p>
 * 提供：
 * <ul>
 *   <li>定期健康检查</li>
 *   <li>导入任务监控</li>
 *   <li>审核队列监控</li>
 *   <li>性能指标收集</li>
 * </ul>
 */
@Slf4j
@Component
public class GraphifyOpsMonitor {
    
    private final GraphifyHealthIndicator healthIndicator;
    private final AtomicLong totalImports = new AtomicLong(0);
    private final AtomicLong successfulImports = new AtomicLong(0);
    private final AtomicLong failedImports = new AtomicLong(0);
    private final AtomicLong totalReviews = new AtomicLong(0);
    
    public GraphifyOpsMonitor(GraphifyHealthIndicator healthIndicator) {
        this.healthIndicator = healthIndicator;
    }
    
    /**
     * 定期检查 Graphify 状态（每 5 分钟）
     */
    @Scheduled(fixedRate = 300000)
    public void periodicHealthCheck() {
        log.debug("Running periodic Graphify health check");
        
        try {
            // 检查是否有卡住的导入任务
            checkStuckImports();
            
            // 检查审核队列积压
            checkReviewQueueBacklog();
            
            // 更新健康指标
            updateHealthMetrics();
            
        } catch (Exception e) {
            log.error("Health check failed", e);
        }
    }
    
    /**
     * 记录导入开始
     */
    public void recordImportStart(String projectId) {
        totalImports.incrementAndGet();
        healthIndicator.setImportInProgress(true);
        log.info("Import started: projectId={}", projectId);
    }
    
    /**
     * 记录导入成功
     */
    public void recordImportSuccess(String projectId, int nodeCount, int edgeCount, long durationMs) {
        successfulImports.incrementAndGet();
        healthIndicator.setImportInProgress(false);
        healthIndicator.updateLastImportStatus("SUCCESS");
        
        log.info("Import completed: projectId={}, nodes={}, edges={}, duration={}ms",
            projectId, nodeCount, edgeCount, durationMs);
    }
    
    /**
     * 记录导入失败
     */
    public void recordImportFailure(String projectId, String reason) {
        failedImports.incrementAndGet();
        healthIndicator.setImportInProgress(false);
        healthIndicator.updateLastImportStatus("FAILED");
        
        log.error("Import failed: projectId={}, reason={}", projectId, reason);
    }
    
    /**
     * 记录审核完成
     */
    public void recordReviewComplete(String edgeId, String decision) {
        totalReviews.incrementAndGet();
        log.info("Review completed: edgeId={}, decision={}", edgeId, decision);
    }
    
    /**
     * 获取运营指标
     */
    public OpsMetrics getOpsMetrics() {
        return new OpsMetrics(
            totalImports.get(),
            successfulImports.get(),
            failedImports.get(),
            totalReviews.get(),
            healthIndicator.getMetrics()
        );
    }
    
    private void checkStuckImports() {
        // 检查是否有超过 1 小时未完成的导入
        long lastImportTime = (Long) healthIndicator.getMetrics().get("lastImportTime");
        if (lastImportTime > 0) {
            long hoursSinceLastImport = Instant.ofEpochMilli(lastImportTime)
                .until(Instant.now(), ChronoUnit.HOURS);
            
            if (hoursSinceLastImport > 1 && (Boolean) healthIndicator.getMetrics().get("importInProgress")) {
                log.warn("Detected stuck import: last update {} hours ago", hoursSinceLastImport);
            }
        }
    }
    
    private void checkReviewQueueBacklog() {
        int pendingCount = (Integer) healthIndicator.getMetrics().get("pendingReviewCount");
        if (pendingCount > 100) {
            log.warn("Review queue backlog: {} pending reviews", pendingCount);
        }
    }
    
    private void updateHealthMetrics() {
        // 可以在这里添加更多的健康检查逻辑
    }
    
    /**
     * 运营指标数据类
     */
    public record OpsMetrics(
        long totalImports,
        long successfulImports,
        long failedImports,
        long totalReviews,
        java.util.Map<String, Object> healthDetails
    ) {
        public double successRate() {
            if (totalImports == 0) return 0.0;
            return (double) successfulImports / totalImports * 100.0;
        }
    }
}
