package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.dto.trace.TraceIngestRequest;
import io.github.legacygraph.dto.trace.TraceTopology;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.RuntimeTrace;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.RuntimeTraceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运行时 trace 接入服务（P2-1）
 * 接收上报的 span，持久化为运行时证据，并：
 * 1) 按服务/操作聚合为运行时调用拓扑；
 * 2) 对图谱中匹配到的节点提升 verifiedScore，作为“运行时验证”信号。
 */
@Slf4j
@Service
public class TraceIngestionService {

    private final RuntimeTraceRepository runtimeTraceRepository;
    private final Neo4jGraphDao neo4jGraphDao;

    public TraceIngestionService(RuntimeTraceRepository runtimeTraceRepository,
                                 Neo4jGraphDao neo4jGraphDao) {
        this.runtimeTraceRepository = runtimeTraceRepository;
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 接收并持久化 span，返回写入条数。
     */
    @Transactional
    public int ingest(String projectId, TraceIngestRequest request) {
        if (request == null || request.getSpans() == null || request.getSpans().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (TraceIngestRequest.SpanDto span : request.getSpans()) {
            RuntimeTrace trace = new RuntimeTrace();
            trace.setProjectId(projectId);
            trace.setVersionId(request.getVersionId());
            trace.setTraceId(span.getTraceId());
            trace.setSpanId(span.getSpanId());
            trace.setParentSpanId(span.getParentSpanId());
            trace.setServiceName(span.getServiceName());
            trace.setOperationName(span.getOperationName());
            trace.setSpanKind(span.getSpanKind());
            trace.setDurationMs(span.getDurationMs());
            trace.setStatus(span.getStatus() != null ? span.getStatus() : "OK");
            if (span.getStartEpochMs() != null) {
                trace.setStartedAt(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(span.getStartEpochMs()), ZoneId.systemDefault()));
            }
            trace.setCreatedAt(LocalDateTime.now());
            runtimeTraceRepository.insert(trace);
            count++;

            // 运行时验证：匹配 operationName + spanKind 到图谱节点
            markRuntimeVerified(projectId, request.getVersionId(), span.getOperationName(), span.getSpanKind());
        }
        log.info("Ingested {} spans for projectId={}, versionId={}", count, projectId, request.getVersionId());
        return count;
    }

    /**
     * 将 span 匹配到的图谱节点标记为运行时已验证。
     * 匹配规则：
     *   HTTP span (SERVER/CLIENT) → 按 operationName 匹配 ApiEndpoint
     *   方法 span (INTERNAL)        → 按 operationName 匹配 Service/Repository/Mapper
     *   SQL span                    → 按 operationName 匹配 Mapper/Table
     * 匹配到的节点设置 verifiedScore=1.0、runtimeVerified=true、lastSeenAt、traceCount+1。
     * 对无法匹配的 trace 记录 unmatched 原因。
     */
    private void markRuntimeVerified(String projectId, String versionId, String operationName, String spanKind) {
        if (operationName == null || operationName.isBlank()) {
            return;
        }
        // 根据 spanKind 确定目标节点类型
        String[] targetTypes;
        if ("SERVER".equals(spanKind) || "CLIENT".equals(spanKind)) {
            targetTypes = new String[]{"ApiEndpoint"};
        } else if ("INTERNAL".equals(spanKind)) {
            targetTypes = new String[]{"Service", "Repository", "Mapper"};
        } else if (spanKind != null && spanKind.toUpperCase().contains("SQL")) {
            targetTypes = new String[]{"Mapper", "Table"};
        } else {
            // 未识别的 spanKind，fallback 到全类型匹配
            targetTypes = new String[]{"ApiEndpoint", "Service", "Repository", "Mapper", "Table"};
        }

        // Query matching nodes from Neo4j — loop over targetTypes since queryNodes supports single type
        List<GraphNode> matched = new ArrayList<>();
        for (String targetType : targetTypes) {
            matched.addAll(neo4jGraphDao.queryNodes(
                    projectId, versionId, targetType, operationName,
                    null, null, null, Integer.MAX_VALUE));
        }
        if (matched.isEmpty()) {
            log.debug("Runtime trace unmatched: projectId={}, operationName={}, spanKind={}",
                    projectId, operationName, spanKind);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (GraphNode node : matched) {
            node.setVerifiedScore(BigDecimal.ONE);
            node.setRuntimeVerified(true);
            node.setLastSeenAt(now);
            int currentCount = node.getTraceCount() != null ? node.getTraceCount() : 0;
            node.setTraceCount(currentCount + 1);
            node.setUpdatedAt(now);
            neo4jGraphDao.updateNode(node);
        }
        log.debug("Runtime verified {} nodes for operationName={}, spanKind={}",
                matched.size(), operationName, spanKind);
    }

    /**
     * 聚合运行时调用拓扑：服务节点 + 服务间调用边。
     */
    public TraceTopology getTopology(String projectId, String versionId) {
        TraceTopology topology = new TraceTopology();
        topology.setProjectId(projectId);
        topology.setVersionId(versionId);

        LambdaQueryWrapper<RuntimeTrace> q = new LambdaQueryWrapper<>();
        q.eq(RuntimeTrace::getProjectId, projectId);
        if (versionId != null) {
            q.eq(RuntimeTrace::getVersionId, versionId);
        }
        List<RuntimeTrace> spans = runtimeTraceRepository.selectList(q);

        topology.setTotalSpans(spans.size());
        topology.setTotalTraces(spans.stream()
                .map(RuntimeTrace::getTraceId).filter(t -> t != null).distinct().count());

        // 服务节点聚合
        Map<String, List<RuntimeTrace>> byService = spans.stream()
                .filter(s -> s.getServiceName() != null)
                .collect(Collectors.groupingBy(RuntimeTrace::getServiceName));

        List<TraceTopology.ServiceNode> serviceNodes = new ArrayList<>();
        for (Map.Entry<String, List<RuntimeTrace>> e : byService.entrySet()) {
            TraceTopology.ServiceNode node = new TraceTopology.ServiceNode();
            node.setName(e.getKey());
            node.setSpanCount(e.getValue().size());
            node.setErrorCount(e.getValue().stream().filter(s -> "ERROR".equals(s.getStatus())).count());
            node.setAvgDurationMs(e.getValue().stream()
                    .map(RuntimeTrace::getDurationMs).filter(d -> d != null)
                    .mapToLong(Long::longValue).average().orElse(0.0));
            serviceNodes.add(node);
        }
        topology.setServices(serviceNodes);

        // 调用边聚合：parent span 的服务 -> child span 的服务
        Map<String, RuntimeTrace> bySpanId = new HashMap<>();
        for (RuntimeTrace s : spans) {
            if (s.getSpanId() != null) {
                bySpanId.put(s.getSpanId(), s);
            }
        }
        Map<String, TraceTopology.CallEdge> edgeMap = new HashMap<>();
        for (RuntimeTrace s : spans) {
            if (s.getParentSpanId() == null) {
                continue;
            }
            RuntimeTrace parent = bySpanId.get(s.getParentSpanId());
            if (parent == null || parent.getServiceName() == null || s.getServiceName() == null) {
                continue;
            }
            if (parent.getServiceName().equals(s.getServiceName())) {
                continue; // 同服务内部调用不计入服务拓扑边
            }
            String key = parent.getServiceName() + "->" + s.getServiceName();
            TraceTopology.CallEdge edge = edgeMap.computeIfAbsent(key, k -> {
                TraceTopology.CallEdge ce = new TraceTopology.CallEdge();
                ce.setFrom(parent.getServiceName());
                ce.setTo(s.getServiceName());
                return ce;
            });
            edge.setCallCount(edge.getCallCount() + 1);
            if ("ERROR".equals(s.getStatus())) {
                edge.setErrorCount(edge.getErrorCount() + 1);
            }
        }
        topology.setCalls(new ArrayList<>(edgeMap.values()));

        return topology;
    }

    /** 供前端"链路列表"使用：返回最近的 span 记录 */
    public List<RuntimeTrace> listRecentTraces(String projectId, String versionId, int limit) {
        LambdaQueryWrapper<RuntimeTrace> q = new LambdaQueryWrapper<>();
        q.eq(RuntimeTrace::getProjectId, projectId);
        if (versionId != null) {
            q.eq(RuntimeTrace::getVersionId, versionId);
        }
        q.orderByDesc(RuntimeTrace::getCreatedAt).last("LIMIT " + Math.max(1, limit));
        return runtimeTraceRepository.selectList(q);
    }
}
