package io.github.legacygraph.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 运行时证据记录 — 将 Trace Span 归一化为统一证据格式。
 * <p>
 * 保存 traceId、scenarioId、spanKind、operationName、httpMethod、
 * path、sqlHash、status、duration 等关键字段。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeEvidenceRecord {

    /** Trace ID */
    private String traceId;

    /** 场景 ID */
    private String scenarioId;

    /** Span 类型 */
    private String spanKind;

    /** 操作名称 */
    private String operationName;

    /** HTTP 方法 */
    private String httpMethod;

    /** 路径 */
    private String path;

    /** SQL 哈希 */
    private String sqlHash;

    /** HTTP 状态码 */
    private Integer httpStatus;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** P95 耗时（毫秒） */
    private Long p95DurationMs;

    /** 错误次数 */
    private Integer errorCount;

    /** 是否已对齐到图谱 */
    private boolean aligned;

    /** 对齐到的节点ID */
    private String matchedNodeId;

    /** 对齐到的边ID */
    private String matchedEdgeId;

    /** 观测时间 */
    private LocalDateTime observedAt;


}
