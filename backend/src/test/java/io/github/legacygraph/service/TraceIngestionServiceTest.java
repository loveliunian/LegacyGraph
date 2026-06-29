package io.github.legacygraph.service;

import io.github.legacygraph.dto.trace.TraceIngestRequest;
import io.github.legacygraph.dto.trace.TraceTopology;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.RuntimeTrace;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.repository.RuntimeTraceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TraceIngestionServiceTest {

    @Mock
    private RuntimeTraceRepository runtimeTraceRepository;
    @Mock
    private GraphNodeRepository graphNodeRepository;

    private TraceIngestionService service;

    @BeforeEach
    void setUp() {
        service = new TraceIngestionService(runtimeTraceRepository, graphNodeRepository);
    }

    private TraceIngestRequest.SpanDto span(String spanId, String parent, String svc, String op, String status) {
        TraceIngestRequest.SpanDto s = new TraceIngestRequest.SpanDto();
        s.setTraceId("trace-1");
        s.setSpanId(spanId);
        s.setParentSpanId(parent);
        s.setServiceName(svc);
        s.setOperationName(op);
        s.setDurationMs(10L);
        s.setStatus(status);
        return s;
    }

    @Test
    void testIngest_PersistsSpansAndMarksVerified() {
        TraceIngestRequest req = new TraceIngestRequest();
        req.setVersionId("v1");
        req.setSpans(Arrays.asList(
                span("s1", null, "gateway", "GET /api/order", "OK"),
                span("s2", "s1", "order-svc", "OrderService.create", "OK")));

        // operationName 匹配到一个图谱节点
        GraphNode node = new GraphNode();
        node.setId("node-1");
        node.setNodeKey("GET /api/order");
        when(graphNodeRepository.selectList(any()))
                .thenReturn(Collections.singletonList(node))  // 第一个 span 命中
                .thenReturn(Collections.emptyList());         // 第二个 span 无命中

        int count = service.ingest("project-1", req);

        assertEquals(2, count);
        verify(runtimeTraceRepository, times(2)).insert(any(RuntimeTrace.class));

        // 命中节点应被标记运行时已验证
        ArgumentCaptor<GraphNode> nodeCaptor = ArgumentCaptor.forClass(GraphNode.class);
        verify(graphNodeRepository).updateById(nodeCaptor.capture());
        assertEquals(0, nodeCaptor.getValue().getVerifiedScore().compareTo(java.math.BigDecimal.ONE));
    }

    @Test
    void testIngest_EmptyRequestReturnsZero() {
        assertEquals(0, service.ingest("p1", null));
        TraceIngestRequest empty = new TraceIngestRequest();
        assertEquals(0, service.ingest("p1", empty));
        verify(runtimeTraceRepository, never()).insert(any(RuntimeTrace.class));
    }

    @Test
    void testGetTopology_AggregatesServicesAndCalls() {
        RuntimeTrace s1 = new RuntimeTrace();
        s1.setSpanId("s1");
        s1.setServiceName("gateway");
        s1.setStatus("OK");
        s1.setDurationMs(5L);
        s1.setTraceId("t1");

        RuntimeTrace s2 = new RuntimeTrace();
        s2.setSpanId("s2");
        s2.setParentSpanId("s1");
        s2.setServiceName("order-svc");
        s2.setStatus("ERROR");
        s2.setDurationMs(15L);
        s2.setTraceId("t1");

        when(runtimeTraceRepository.selectList(any())).thenReturn(new ArrayList<>(Arrays.asList(s1, s2)));

        TraceTopology topo = service.getTopology("project-1", "v1");

        assertEquals(2, topo.getTotalSpans());
        assertEquals(1, topo.getTotalTraces());
        assertEquals(2, topo.getServices().size());
        // 一条 gateway -> order-svc 的调用边，含 1 次错误
        assertEquals(1, topo.getCalls().size());
        TraceTopology.CallEdge edge = topo.getCalls().get(0);
        assertEquals("gateway", edge.getFrom());
        assertEquals("order-svc", edge.getTo());
        assertEquals(1, edge.getCallCount());
        assertEquals(1, edge.getErrorCount());
    }
}
