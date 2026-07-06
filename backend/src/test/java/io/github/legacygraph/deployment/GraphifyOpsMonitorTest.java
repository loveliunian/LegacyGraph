package io.github.legacygraph.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphifyOpsMonitorTest {
    
    private GraphifyOpsMonitor monitor;
    private GraphifyHealthIndicator healthIndicator;
    
    @BeforeEach
    void setUp() {
        healthIndicator = new GraphifyHealthIndicator();
        monitor = new GraphifyOpsMonitor(healthIndicator);
    }
    
    @Test
    void recordImportStartIncrementsCounter() {
        monitor.recordImportStart("project-1");
        
        GraphifyOpsMonitor.OpsMetrics metrics = monitor.getOpsMetrics();
        
        assertThat(metrics.totalImports()).isEqualTo(1);
    }
    
    @Test
    void recordImportSuccessIncrementsSuccessCounter() {
        monitor.recordImportStart("project-1");
        monitor.recordImportSuccess("project-1", 100, 500, 5000);
        
        GraphifyOpsMonitor.OpsMetrics metrics = monitor.getOpsMetrics();
        
        assertThat(metrics.successfulImports()).isEqualTo(1);
    }
    
    @Test
    void recordImportFailureIncrementsFailureCounter() {
        monitor.recordImportStart("project-1");
        monitor.recordImportFailure("project-1", "Connection timeout");
        
        GraphifyOpsMonitor.OpsMetrics metrics = monitor.getOpsMetrics();
        
        assertThat(metrics.failedImports()).isEqualTo(1);
    }
    
    @Test
    void successRateCalculation() {
        monitor.recordImportStart("project-1");
        monitor.recordImportSuccess("project-1", 100, 500, 5000);
        
        monitor.recordImportStart("project-2");
        monitor.recordImportSuccess("project-2", 200, 800, 8000);
        
        monitor.recordImportStart("project-3");
        monitor.recordImportFailure("project-3", "Parse error");
        
        GraphifyOpsMonitor.OpsMetrics metrics = monitor.getOpsMetrics();
        
        // 2 成功 / 3 总计 = 66.67%
        assertThat(metrics.successRate()).isBetween(66.0, 67.0);
    }
    
    @Test
    void zeroImportsReturnsZeroSuccessRate() {
        GraphifyOpsMonitor.OpsMetrics metrics = monitor.getOpsMetrics();
        
        assertThat(metrics.successRate()).isZero();
    }
    
    @Test
    void recordReviewCompleteIncrementsCounter() {
        monitor.recordReviewComplete("edge-123", "ACCEPTED");
        monitor.recordReviewComplete("edge-456", "REJECTED");
        
        GraphifyOpsMonitor.OpsMetrics metrics = monitor.getOpsMetrics();
        
        assertThat(metrics.totalReviews()).isEqualTo(2);
    }
    
    @Test
    void opsMetricsIncludesHealthDetails() {
        healthIndicator.setImportInProgress(true);
        healthIndicator.setPendingReviewCount(5);
        
        GraphifyOpsMonitor.OpsMetrics metrics = monitor.getOpsMetrics();
        
        assertThat(metrics.healthDetails()).isNotNull();
        assertThat(metrics.healthDetails()).containsEntry("importInProgress", true);
        assertThat(metrics.healthDetails()).containsEntry("pendingReviewCount", 5);
    }
    
    @Test
    void multipleImportsAreTracked() {
        for (int i = 1; i <= 10; i++) {
            monitor.recordImportStart("project-" + i);
            if (i % 2 == 0) {
                monitor.recordImportSuccess("project-" + i, 100, 500, 5000);
            } else {
                monitor.recordImportFailure("project-" + i, "Error");
            }
        }
        
        GraphifyOpsMonitor.OpsMetrics metrics = monitor.getOpsMetrics();
        
        assertThat(metrics.totalImports()).isEqualTo(10);
        assertThat(metrics.successfulImports()).isEqualTo(5);
        assertThat(metrics.failedImports()).isEqualTo(5);
    }
}
