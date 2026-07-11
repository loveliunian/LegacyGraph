package io.github.legacygraph.dto.requirement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 需求-表/字段反向溯源响应 DTO（G-16）。
 * <p>从需求出发沿 AFFECTS 边找到目标节点，再反向追溯 CALLS/READS/WRITES/MAPS_TO/HAS_COLUMN
 * 等边到达 Table/Column 节点，聚合为受影响表清单与汇总摘要。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataLineageResponse {

    /** 受影响的表清单（按风险分数降序排列） */
    private List<TableImpact> tables;

    /** 聚合摘要 */
    private Summary summary;

    /**
     * 单张受影响表的影响详情。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableImpact {
        /** 表节点 key（通常为 schema.table 形式） */
        private String tableKey;
        /** 表名称 */
        private String tableName;
        /** 受影响的字段名列表（反向追溯命中的 Column 节点名称） */
        private List<String> impactedColumns;
        /** 该表的风险分数 [0,1]：直接命中=1.0，反向追溯命中=0.7，字段命中=0.5 */
        private double riskScore;
        /** 关联证据节点 ID 列表（来自 Table 节点的 evidenceIds 字段） */
        private List<String> evidenceIds;
    }

    /**
     * 数据血缘聚合摘要。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        /** 受影响表数量 */
        private int tableCount;
        /** 受影响字段总数（去重后的字段数） */
        private int columnCount;
        /** 全表中的最大风险分数 */
        private double maxRiskScore;
    }
}
