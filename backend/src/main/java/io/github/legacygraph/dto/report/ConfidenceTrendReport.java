package io.github.legacygraph.dto.report;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 置信度趋势报告
 * 跟踪项目置信度随时间变化趋势
 */
@Data
public class ConfidenceTrendReport {

    private String projectId;
    private String versionId;
    private LocalDate startDate;
    private LocalDate endDate;

    // 每日数据点
    private List<DailyData> dailyData;

    // 趋势分析
    private BigDecimal startingAverageConfidence;
    private BigDecimal endingAverageConfidence;
    private BigDecimal totalImprovement;
    private String trendDirection;  // UP / FLAT / DOWN

    @Data
    public static class DailyData {
        private LocalDate date;
        private BigDecimal averageConfidence;
        private long confirmedNodes;
        private long newNodes;
    }
}
