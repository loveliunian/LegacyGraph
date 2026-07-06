package io.github.legacygraph.dao;

import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Neo4jWriteRepositoryTest {

    @Test
    void batchNodeMergeRefreshesMetadataOnMatch() {
        Driver driver = mock(Driver.class);
        Session session = mock(Session.class);
        Result result = mock(Result.class);
        when(driver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(false);

        Neo4jWriteRepository repository = new Neo4jWriteRepository(driver);
        GraphNode node = new GraphNode();
        node.setId("node-1");
        node.setProjectId("project-1");
        node.setVersionId("v1");
        node.setNodeType("Column");
        node.setNodeKey("public.orders.status");
        node.setNodeName("status");
        node.setDisplayName("status");
        node.setDescription("订单状态");
        node.setSourceType("DB_METADATA");
        node.setProperties("{\"semanticType\":\"status\"}");

        repository.mergeNodesBatch(List.of(node));

        ArgumentCaptor<String> cypher = ArgumentCaptor.forClass(String.class);
        verify(session).run(cypher.capture(), anyMap());
        assertThat(cypher.getValue()).contains("ON MATCH SET");
        assertThat(cypher.getValue()).contains("n.nodeName = row.nodeName");
        assertThat(cypher.getValue()).contains("n.description = row.description");
        assertThat(cypher.getValue()).contains("n.properties = row.properties");
        assertThat(cypher.getValue()).contains("n.status = row.status");
    }

    @Test
    void batchEdgeMergeRefreshesMetadataOnMatch() {
        Driver driver = mock(Driver.class);
        Session session = mock(Session.class);
        Result result = mock(Result.class);
        when(driver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(false);

        Neo4jWriteRepository repository = new Neo4jWriteRepository(driver);
        GraphEdge edge = new GraphEdge();
        edge.setId("edge-1");
        edge.setFromNodeId("from-1");
        edge.setToNodeId("to-1");
        edge.setProjectId("project-1");
        edge.setVersionId("v1");
        edge.setEdgeType("REFERENCES");
        edge.setEdgeKey("orders->references->customers");
        edge.setSourceType("DB_METADATA");
        edge.setConfidence(BigDecimal.ONE);
        edge.setStatus("CONFIRMED");
        edge.setProperties("{\"constraintName\":\"fk_order_customer\"}");

        repository.mergeEdgesBatch(List.of(edge));

        ArgumentCaptor<String> cypher = ArgumentCaptor.forClass(String.class);
        verify(session).run(cypher.capture(), anyMap());
        assertThat(cypher.getValue()).contains("ON MATCH SET");
        assertThat(cypher.getValue()).contains("r.sourceType = row.sourceType");
        assertThat(cypher.getValue()).contains("r.confidence = row.confidence");
        assertThat(cypher.getValue()).contains("r.status = row.status");
        assertThat(cypher.getValue()).contains("r.properties = row.properties");
    }
}
