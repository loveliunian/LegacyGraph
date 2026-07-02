package io.github.legacygraph.builder;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.FeatureSlice;
import io.github.legacygraph.dto.graph.ScenarioDSL;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ScenarioDSLBuilder 单元测试。
 * 验证从 FeatureSlice 生成 API/DB 场景 DSL。
 */
@ExtendWith(MockitoExtension.class)
class ScenarioDSLBuilderTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @InjectMocks
    private ScenarioDSLBuilder builder;

    /**
     * 测试空切片时返回空列表。
     */
    @Test
    void buildFromSlice_nullSlice_returnsEmpty() {
        List<ScenarioDSL> result = builder.buildFromSlice(null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * 测试切片无 sliceId 时返回空列表。
     */
    @Test
    void buildFromSlice_noSliceId_returnsEmpty() {
        FeatureSlice slice = FeatureSlice.builder().build();

        List<ScenarioDSL> result = builder.buildFromSlice(slice);

        assertTrue(result.isEmpty());
    }

    /**
     * 测试从含 API 的切片生成 API 场景。
     */
    @Test
    void buildFromSlice_withApiGen_generatesApiScenario() {
        GraphNode apiNode = new GraphNode();
        apiNode.setId("api-1");
        apiNode.setNodeKey("POST /api/orders");
        apiNode.setNodeName("POST /api/orders");
        apiNode.setDisplayName("创建订单 API");
        when(neo4jGraphDao.findNodeById("api-1")).thenReturn(Optional.of(apiNode));

        FeatureSlice slice = FeatureSlice.builder()
                .sliceId("slice-1")
                .projectId("project-1")
                .name("订单创建")
                .featureName("订单创建")
                .entryPage("/orders")
                .apiIds(List.of("api-1"))
                .sqlIds(List.of("sql-1"))
                .tableIds(List.of("table-1"))
                .build();

        List<ScenarioDSL> result = builder.buildFromSlice(slice);

        assertEquals(1, result.size());
        ScenarioDSL dsl = result.get(0);
        assertEquals("API", dsl.getScenarioType());
        assertEquals("POST", dsl.getHttpMethod());
        assertEquals("/api/orders", dsl.getApiPath());
        assertEquals("user", dsl.getRole());
        assertEquals("/orders", dsl.getEntryPath());
        assertNotNull(dsl.getActions());
        assertNotNull(dsl.getAssertions());
        assertTrue(dsl.getAssertions().stream().anyMatch(a -> "http_status".equals(a.getType())));
    }

    /**
     * 测试无 API 但有表时生成 DB 场景。
     */
    @Test
    void buildFromSlice_withoutApiButWithTables_generatesDbScenario() {
        GraphNode tableNode = new GraphNode();
        tableNode.setId("table-1");
        tableNode.setNodeName("orders");
        tableNode.setDisplayName("订单表");
        when(neo4jGraphDao.findNodeById("table-1")).thenReturn(Optional.of(tableNode));

        FeatureSlice slice = FeatureSlice.builder()
                .sliceId("slice-2")
                .projectId("project-1")
                .name("报表查询")
                .featureName("报表查询")
                .tableIds(List.of("table-1"))
                .build();

        List<ScenarioDSL> result = builder.buildFromSlice(slice);

        assertEquals(1, result.size());
        ScenarioDSL dsl = result.get(0);
        assertEquals("DB", dsl.getScenarioType());
        assertTrue(dsl.getAssertions().stream().anyMatch(a -> "db".equals(a.getType())));
    }
}
