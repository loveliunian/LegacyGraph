package io.github.legacygraph.service;

import io.github.legacygraph.dto.trace.TraceIngestRequest;
import io.github.legacygraph.dto.trace.TraceTopology;
import io.github.legacygraph.builder.TraceGraphAligner;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.RuntimeTrace;
import io.github.legacygraph.dao.Neo4jGraphDao;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TraceIngestionServiceTest {

    @Mock
    private RuntimeTraceRepository runtimeTraceRepository;
    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private TraceGraphAligner traceGraphAligner;

    private TraceIngestionService service;

    @BeforeEach
    void setUp() {
        service = new TraceIngestionService(runtimeTraceRepository, neo4jGraphDao, traceGraphAligner);
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
    void testIngest_PersistsSpansAndAlignsRuntimeEvidence() {
        TraceIngestRequest req = new TraceIngestRequest();
        req.setVersionId("v1");
        req.setSpans(Arrays.asList(
                span("s1", null, "gateway", "GET /api/order", "OK"),
                span("s2", "s1", "order-svc", "OrderService.create", "OK")));

        int count = service.ingest("project-1", req);

        assertEquals(2, count);
        verify(runtimeTraceRepository, times(2)).insert(any(RuntimeTrace.class));

        verify(traceGraphAligner).align(eq("project-1"), eq("v1"), argThat(records ->
                records.size() == 2
                        && "GET /api/order".equals(records.get(0).getOperationName())
                        && "GET".equals(records.get(0).getHttpMethod())
                        && "/api/order".equals(records.get(0).getPath())
        ));
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
        assertEquals(1, topo.getCalls().size());
        TraceTopology.CallEdge edge = topo.getCalls().get(0);
        assertEquals("gateway", edge.getFrom());
        assertEquals("order-svc", edge.getTo());
        assertEquals(1, edge.getCallCount());
        assertEquals(1, edge.getErrorCount());
    }
}
