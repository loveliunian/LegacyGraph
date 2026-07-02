package io.github.legacygraph.dto.report;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 资料扫描研究报告 DTO。
 */
@Data
@Builder
public class ScanResearchReport {

    /** 扫描输入范围 */
    private Map<String, Object> scanInput;

    /** Adapter 执行统计 */
    @Builder.Default
    private List<AdapterStat> adapterStats = new ArrayList<>();

    /** 数据库扫描统计 */
    private Map<String, Object> dbScanStats;

    /** 图谱写入统计 */
    private Map<String, Object> graphWriteStats;

    /** Claim 统计 */
    private Map<String, Object> claimStats;

    /** 三类图谱覆盖 */
    private Map<String, Object> graphCoverage;

    /** 缺口清单 */
    @Builder.Default
    private List<Map<String, Object>> gapList = new ArrayList<>();

    /** 不确定性声明 */
    @Builder.Default
    private List<String> uncertaintyNotes = new ArrayList<>();

    @Data
    @Builder
    public static class AdapterStat {
        private String adapterName;
        private int processedAssets;
        private int skippedAssets;
        private int failedAssets;
        private int nodeCount;
        private int edgeCount;
        private int evidenceCount;
    }
}
