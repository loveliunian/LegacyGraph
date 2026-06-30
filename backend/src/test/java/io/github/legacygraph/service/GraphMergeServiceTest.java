package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
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
    private Neo4jGraphDao neo4jGraphDao;

    private GraphMergeService graphMergeService;

    @Test
    void testConstruction() {
        graphMergeService = new GraphMergeService(neo4jGraphDao);
        assertNotNull(graphMergeService);
    }
}
