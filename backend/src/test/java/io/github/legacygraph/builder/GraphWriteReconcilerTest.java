package io.github.legacygraph.builder;

import io.github.legacygraph.repository.EdgeEvidenceRepository;
import io.github.legacygraph.repository.NodeEvidenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphWriteReconcilerTest {

    @Mock private Driver driver;
    @Mock private NodeEvidenceRepository nodeEvidenceRepository;
    @Mock private EdgeEvidenceRepository edgeEvidenceRepository;
    @Mock private Session nodeScanSession;
    @Mock private Session edgeScanSession;
    @Mock private Session clearSession;
    @Mock private Result nodeResult;
    @Mock private Result edgeResult;
    @Mock private org.neo4j.driver.Record nodeRecord;

    @Test
    void reconcileKeepsIncompleteNodeWhenEvidenceIsStillMissing() {
        when(driver.session()).thenReturn(nodeScanSession, edgeScanSession);
        stubOneIncompleteNode();
        when(edgeScanSession.run(anyString(), org.mockito.ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn(edgeResult);
        when(edgeResult.hasNext()).thenReturn(false);
        when(nodeEvidenceRepository.selectCount(any())).thenReturn(0L);

        GraphWriteReconciler reconciler = new GraphWriteReconciler(driver, nodeEvidenceRepository, edgeEvidenceRepository);

        GraphWriteReconciler.ReconciliationResult result = reconciler.reconcile("p1");

        assertEquals(1, result.incompleteNodes().size());
        assertEquals(0, result.autoFixed());
        verify(clearSession, never()).run(contains("REMOVE n.writeStatus"),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any());
    }

    @Test
    void reconcileClearsIncompleteNodeOnlyAfterEvidenceExists() {
        when(driver.session()).thenReturn(nodeScanSession, edgeScanSession, clearSession);
        stubOneIncompleteNode();
        when(edgeScanSession.run(anyString(), org.mockito.ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn(edgeResult);
        when(edgeResult.hasNext()).thenReturn(false);
        when(nodeEvidenceRepository.selectCount(any())).thenReturn(1L);

        GraphWriteReconciler reconciler = new GraphWriteReconciler(driver, nodeEvidenceRepository, edgeEvidenceRepository);

        GraphWriteReconciler.ReconciliationResult result = reconciler.reconcile("p1");

        assertEquals(1, result.incompleteNodes().size());
        assertEquals(1, result.autoFixed());
        verify(clearSession).run(contains("REMOVE n.writeStatus"),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any());
    }

    private void stubOneIncompleteNode() {
        when(nodeScanSession.run(anyString(), org.mockito.ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn(nodeResult);
        when(nodeResult.hasNext()).thenReturn(true, false);
        when(nodeResult.next()).thenReturn(nodeRecord);
        when(nodeRecord.get("nodeId")).thenReturn(Values.value("n1"));
        when(nodeRecord.get("nodeType")).thenReturn(Values.value("CLASS"));
        when(nodeRecord.get("nodeKey")).thenReturn(Values.value("OrderService"));
        when(nodeRecord.get("projectId")).thenReturn(Values.value("p1"));
        when(nodeRecord.containsKey("writeError")).thenReturn(false);
    }
}
