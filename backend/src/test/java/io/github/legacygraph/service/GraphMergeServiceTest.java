package io.github.legacygraph.service;

import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.GraphEdge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphMergeServiceTest {

    @Mock
    private GraphNodeRepository graphNodeRepository;
    @Mock
    private GraphEdgeRepository graphEdgeRepository;

    private GraphMergeService graphMergeService;

    @Test
    void testConstruction() {
        graphMergeService = new GraphMergeService(graphNodeRepository, graphEdgeRepository);
        assertNotNull(graphMergeService);
    }
}
