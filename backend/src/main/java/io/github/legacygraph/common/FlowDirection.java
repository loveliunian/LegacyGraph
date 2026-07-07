package io.github.legacygraph.common;

/**
 * 有向遍历方向 — 用于变更影响多跳反查。
 */
public enum FlowDirection {
    /** 反向：往上游依赖（如 Table ← SQL ← Mapper ← Service） */
    INBOUND,
    /** 正向：往下游被依赖 */
    OUTBOUND
}
