package io.github.legacygraph.dto.trace;

import lombok.Data;

import java.util.List;

/**
 * 运行时服务拓扑响应
 * 由 span 聚合而成：节点为服务，边为服务间调用（含调用次数与错误率）。
 */
@Data
public class TraceTopology {

    private String projectId;
    private String versionId;
    private long totalSpans;
    private long totalTraces;
    private List<ServiceNode> services;
    private List<CallEdge> calls;

    @Data
    public static class ServiceNode {
        private String name;
        private long spanCount;
        private long errorCount;
        private double avgDurationMs;
    }

    @Data
    public static class CallEdge {
        private String from;
        private String to;
        private long callCount;
        private long errorCount;
    }
}
