package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.service.graph.GraphDiffReadModel;
import io.github.legacygraph.service.graph.GraphDiffReadModel.DiffItem;
import io.github.legacygraph.service.graph.GraphDiffReadModel.DiffResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphDiffControllerTest {

    @Mock
    private GraphDiffReadModel graphDiffReadModel;

    private GraphDiffController controller;

    @BeforeEach
    void setUp() {
        controller = new GraphDiffController(graphDiffReadModel);
    }

    @Test
    void diff_returnsDiffResult() {
        List<DiffItem> addedNodes = List.of(
                new DiffItem("n1", "CLASS", "NewService", "NODE")
        );
        List<DiffItem> removedNodes = List.of(
                new DiffItem("n2", "CLASS", "OldService", "NODE")
        );
        List<DiffItem> addedEdges = List.of(
                new DiffItem("e1", "CALLS", "A->B", "EDGE")
        );
        List<DiffItem> removedEdges = List.of();

        DiffResult diffResult = new DiffResult(addedNodes, removedNodes, addedEdges, removedEdges);
        when(graphDiffReadModel.diffVersions("p1", "v1", "v2")).thenReturn(diffResult);

        Result<DiffResult> result = controller.diff("p1", "v1", "v2");

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals(1, result.getData().addedNodes().size());
        assertEquals(1, result.getData().removedNodes().size());
        assertEquals(1, result.getData().addedEdges().size());
        assertEquals(0, result.getData().removedEdges().size());
        verify(graphDiffReadModel).diffVersions("p1", "v1", "v2");
    }

    @Test
    void diff_noDifferences_returnsEmptyLists() {
        DiffResult empty = new DiffResult(List.of(), List.of(), List.of(), List.of());
        when(graphDiffReadModel.diffVersions("p1", "v1", "v2")).thenReturn(empty);

        Result<DiffResult> result = controller.diff("p1", "v1", "v2");

        assertEquals(0, result.getCode());
        assertTrue(result.getData().addedNodes().isEmpty());
        assertTrue(result.getData().removedNodes().isEmpty());
        assertTrue(result.getData().addedEdges().isEmpty());
        assertTrue(result.getData().removedEdges().isEmpty());
    }

    @Test
    void diff_multipleDifferences() {
        List<DiffItem> addedNodes = List.of(
                new DiffItem("n1", "CLASS", "ServiceA", "NODE"),
                new DiffItem("n2", "CLASS", "ServiceB", "NODE"),
                new DiffItem("n3", "METHOD", "methodC", "NODE")
        );
        DiffResult diffResult = new DiffResult(addedNodes, List.of(), List.of(), List.of());
        when(graphDiffReadModel.diffVersions("p1", "v1", "v3")).thenReturn(diffResult);

        Result<DiffResult> result = controller.diff("p1", "v1", "v3");

        assertEquals(3, result.getData().addedNodes().size());
    }
}
