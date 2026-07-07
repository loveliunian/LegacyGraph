package io.github.legacygraph.common;

import java.util.List;

/**
 * 变更影响反向遍历方向 — 决定多跳反查的边类型集合与方向。
 * <p>
 * 对齐 {@code doc/系统关系总览/01-关系总览与映射框架.md} §3 核心链路：
 * Table←READS/WRITE←SqlStatement←EXECUTES←Mapper←CALLS←Service←HANDLED_BY←ApiEndpoint←EXPOSED_BY←Feature
 * </p>
 */
public enum TraversalDirection {
    /** 从 Table 反查到 Feature（加字段/改表场景） */
    TABLE_REVERSE,
    /** 从 ApiEndpoint 反查到 Feature / 下游调用方 */
    API_REVERSE,
    /** 从 Service 反查到 Api / Feature */
    SERVICE_REVERSE;

    public List<String> edgeTypes() {
        return switch (this) {
            case TABLE_REVERSE -> List.of("READS", "WRITES", "EXECUTES", "CALLS", "HANDLED_BY", "EXPOSED_BY", "IMPLEMENTED_BY");
            case API_REVERSE -> List.of("HANDLED_BY", "CALLS", "EXECUTES", "READS", "WRITES");
            case SERVICE_REVERSE -> List.of("CALLS", "EXECUTES", "READS", "WRITES", "HANDLED_BY");
        };
    }

    public FlowDirection flow() {
        return FlowDirection.INBOUND;
    }
}
