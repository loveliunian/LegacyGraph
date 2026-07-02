package io.github.legacygraph.dto.graph;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 图谱写入意图（Outbox 模式）。
 * <p>
 * 调用方通过此 DTO 声明"需要写入图谱"，包含节点/边 claims、证据、幂等键。
 * 当前由 {@code EvidenceGraphWriter#writeIntent} 批量处理，写入失败时由
 * {@code GraphWriteReconciler} 复核 INCOMPLETE 标记。
 * </p>
 *
 * @see io.github.legacygraph.builder.EvidenceGraphWriter#writeIntent(GraphWriteIntent)
 * @see io.github.legacygraph.builder.GraphWriteReconciler
 */
@Data
@Builder
public class GraphWriteIntent {

    /** 幂等键：同一意图重复提交不产生副作用 */
    private String idempotencyKey;

    /** 项目ID */
    private String projectId;

    /** 扫描版本ID */
    private String versionId;

    /** 节点声明列表 */
    private List<GraphNodeClaim> nodeClaims;

    /** 边声明列表 */
    private List<GraphEdgeClaim> edgeClaims;

    /** 证据记录列表 */
    private List<EvidenceRecord> evidenceRecords;

    /** 写入来源（SCAN / AI / MANUAL） */
    private String source;

    /** 创建时间 */
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
