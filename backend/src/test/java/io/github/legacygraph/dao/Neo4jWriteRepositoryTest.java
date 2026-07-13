package io.github.legacygraph.dao;

import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionContext;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        TransactionContext txContext = mock(TransactionContext.class);
        when(driver.session()).thenReturn(session);
        // 预先 stub TransactionContext.run() 返回 result，避免回调内 tx.run() 返回 null 触发 NPE
        when(txContext.run(anyString(), anyMap())).thenReturn(result);
        when(session.executeWrite(any(TransactionCallback.class), any(TransactionConfig.class)))
                .thenAnswer(inv -> {
                    TransactionCallback<Integer> cb = inv.getArgument(0);
                    return cb.execute(txContext);
                });
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

        // executeWrite 内部会调用 tx.run，验证 cypher 通过 Transaction 传递
        assertThat(node.getNodeKey()).isEqualTo("public.orders.status");
    }

    @Test
    void batchEdgeMergeRefreshesMetadataOnMatch() {
        Driver driver = mock(Driver.class);
        Session session = mock(Session.class);
        Result result = mock(Result.class);
        TransactionContext txContext = mock(TransactionContext.class);
        when(driver.session()).thenReturn(session);
        when(txContext.run(anyString(), anyMap())).thenReturn(result);
        when(session.executeWrite(any(TransactionCallback.class), any(TransactionConfig.class)))
                .thenAnswer(inv -> {
                    TransactionCallback<Integer> cb = inv.getArgument(0);
                    return cb.execute(txContext);
                });
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

        assertThat(edge.getEdgeKey()).isEqualTo("orders->references->customers");
    }
}
