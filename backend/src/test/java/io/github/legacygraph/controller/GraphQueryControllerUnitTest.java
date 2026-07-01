package io.github.legacygraph.controller;

import io.github.legacygraph.builder.FeatureSliceBuilder;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.FeatureSlice;
import io.github.legacygraph.service.GraphMergeService;
import io.github.legacygraph.service.GraphQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphQueryControllerUnitTest {

    @Mock
    private GraphQueryService graphQueryService;

    @Mock
    private GraphMergeService graphMergeService;

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @Mock
    private FeatureSliceBuilder featureSliceBuilder;

    @Test
    void getFeatureSlicesDelegatesToFeatureSliceBuilder() {
        FeatureSlice slice = FeatureSlice.builder()
                .sliceId("feature-1")
                .name("订单创建")
                .featureName("订单创建")
                .coverageStatus("PARTIAL")
                .build();
        when(featureSliceBuilder.buildAllSlices("project-1", "v1")).thenReturn(List.of(slice));

        GraphQueryController controller = newController();

        Result<List<FeatureSlice>> result = controller.getFeatureSlices("project-1", "v1");

        assertEquals(0, result.getCode());
        assertEquals("feature-1", result.getData().get(0).getSliceId());
    }

    @Test
    void getGraphQualityReportReturnsVersionStats() {
        when(neo4jGraphDao.versionGraphStats("project-1", "v1"))
                .thenReturn(Map.of("totalNodes", 2L, "pendingNodes", 1L));

        GraphQueryController controller = newController();

        Result<Map<String, Object>> result = controller.getGraphQualityReport("project-1", "v1");

        assertEquals(0, result.getCode());
        assertEquals(2L, result.getData().get("totalNodes"));
    }

    @Test
    void getDriftQueueReturnsStaticOnlyEdges() {
        Map<String, Object> driftQueue = Map.of(
                "items", List.of(Map.of("id", "edge-1", "type", "static_only")),
                "summary", Map.of("staticOnly", 1L));
        when(graphQueryService.getDriftQueue("project-1", "static_only")).thenReturn(driftQueue);

        GraphQueryController controller = newController();

        Result<Map<String, Object>> result = controller.getDriftQueue("project-1", "static_only");

        assertEquals(0, result.getCode());
        List<?> items = (List<?>) result.getData().get("items");
        assertFalse(items.isEmpty());
        assertEquals(1L, ((Map<?, ?>) result.getData().get("summary")).get("staticOnly"));
    }

    private GraphQueryController newController() {
        return new GraphQueryController(graphQueryService, graphMergeService, neo4jGraphDao, featureSliceBuilder);
    }
}
