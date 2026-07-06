package io.github.legacygraph.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphifyHealthIndicatorTest {
    
    private GraphifyHealthIndicator healthIndicator;
    
    @BeforeEach
    void setUp() {
        healthIndicator = new GraphifyHealthIndicator();
    }
    
    @Test
    void initialStateIsUp() {
        Health health = healthIndicator.health();
        
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("importInProgress", false);
        assertThat(health.getDetails()).containsEntry("lastImportStatus", "NEVER_RUN");
    }
    
    @Test
    void importInProgressReturnsUp() {
        healthIndicator.setImportInProgress(true);
        
        Health health = healthIndicator.health();
        
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("importInProgress", true);
    }
    
    @Test
    void lastImportFailedReturnsDown() {
        healthIndicator.updateLastImportStatus("FAILED");
        
        Health health = healthIndicator.health();
        
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("lastImportStatus", "FAILED");
        assertThat(health.getDetails()).containsEntry("reason", "Last import failed");
    }
    
    @Test
    void importInProgressOverridesFailure() {
        healthIndicator.updateLastImportStatus("FAILED");
        healthIndicator.setImportInProgress(true);
        
        Health health = healthIndicator.health();
        
        // 即使上次失败，如果有新的导入在进行中，状态应该是 UP
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
    }
    
    @Test
    void lastImportSuccessReturnsUp() {
        healthIndicator.updateLastImportStatus("SUCCESS");
        
        Health health = healthIndicator.health();
        
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("lastImportStatus", "SUCCESS");
    }
    
    @Test
    void metricsIncludeAllFields() {
        healthIndicator.setImportInProgress(true);
        healthIndicator.updateLastImportStatus("SUCCESS");
        healthIndicator.setPendingReviewCount(5);
        
        Map<String, Object> metrics = healthIndicator.getMetrics();
        
        assertThat(metrics).containsKeys(
            "importInProgress",
            "lastImportStatus",
            "lastImportTime",
            "pendingReviewCount"
        );
        assertThat(metrics).containsEntry("importInProgress", true);
        assertThat(metrics).containsEntry("lastImportStatus", "SUCCESS");
        assertThat(metrics).containsEntry("pendingReviewCount", 5);
    }
    
    @Test
    void pendingReviewCountCanBeUpdated() {
        healthIndicator.setPendingReviewCount(10);
        
        Map<String, Object> metrics = healthIndicator.getMetrics();
        
        assertThat(metrics).containsEntry("pendingReviewCount", 10);
    }
}
