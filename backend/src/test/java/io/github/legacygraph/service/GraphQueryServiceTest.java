package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GraphQueryService 测试（Phase 2.6 更新：Driver 已移除，全部委托读模型）。
 */
@ExtendWith(MockitoExtension.class)
class GraphQueryServiceTest {

    @Mock private Neo4jGraphDao neo4jGraphDao;
    @Mock private ScanVersionRepository scanVersionRepository;
    @Mock private ScanTaskRepository scanTaskRepository;
    @Mock private FactRepository factRepository;
    @Mock private CacheService cacheService;
    @Mock private GraphPathReadModel pathReadModel;
    @Mock private GraphProjectionReadModel projectionReadModel;

    private GraphQueryService graphQueryService;

    @BeforeEach
    void setUp() {
        graphQueryService = new GraphQueryService(neo4jGraphDao, scanVersionRepository,
                scanTaskRepository, factRepository, cacheService,
                pathReadModel, projectionReadModel);
        lenient().when(cacheService.getOrLoad(anyString(), any(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(3)).get());
    }

    @Test void testConstruction() { assertNotNull(graphQueryService); }

    @Test
    void testGetApiCallChain_DelegatesToReadModel() {
        var chain = new GraphPathReadModel.PathChain(); chain.nodes = List.of();
        when(pathReadModel.getApiCallChain(anyString(), anyString(), anyString())).thenReturn(chain);
        List<Map<String, Object>> result = graphQueryService.getApiCallChain("p1", "v1", "GET /api/test");
        assertNotNull(result);
        verify(pathReadModel).getApiCallChain("p1", "v1", "GET /api/test");
    }

    @Test
    void testGetTableImpact_DelegatesToReadModel() {
        var chain = new GraphPathReadModel.PathChain(); chain.nodes = List.of();
        when(pathReadModel.getTableImpact(anyString(), anyString(), anyString())).thenReturn(chain);
        List<Map<String, Object>> result = graphQueryService.getTableImpact("p1", "v1", "orders");
        assertNotNull(result);
        verify(pathReadModel).getTableImpact("p1", "v1", "orders");
    }

    @Test
    void testGetFeatureView_DelegatesToReadModel() {
        var view = new GraphProjectionReadModel.ProjectionView();
        when(projectionReadModel.getFeatureView(anyString(), anyString(), anyString())).thenReturn(view);
        Map<String, Object> result = graphQueryService.getFeatureView("p1", "v1", "order");
        assertNotNull(result);
        verify(projectionReadModel).getFeatureView("p1", "v1", "order");
    }

    @Test
    void testGetBusinessView_DelegatesToReadModel() {
        var view = new GraphProjectionReadModel.ProjectionView();
        when(projectionReadModel.getBusinessView(anyString(), anyString(), anyString())).thenReturn(view);
        Map<String, Object> result = graphQueryService.getBusinessView("p1", "v1", "sales");
        assertNotNull(result);
        verify(projectionReadModel).getBusinessView("p1", "v1", "sales");
    }

    @Test
    void testGetScanVersions_usesSnapshotForTerminalStatus() {
        // 终态版本有 stats_updated_at → 应直接读快照字段，不查 Neo4j/ScanTask/Fact
        ScanVersion v = new ScanVersion();
        v.setId("v-1");
        v.setProjectId("p1");
        v.setVersionNo("1.0");
        v.setScanStatus("SUCCESS");
        v.setStatsUpdatedAt(LocalDateTime.now());
        v.setTaskTotal(5); v.setTaskSuccess(5); v.setTaskFailed(0);
        v.setCurrentStage("COMPLETED");
        v.setNodeCount(100L); v.setEdgeCount(200L); v.setFactCount(50L);
        v.setCreatedAt(LocalDateTime.now());
        v.setStartedAt(LocalDateTime.now());
        v.setFinishedAt(LocalDateTime.now());

        Page<ScanVersion> p = new Page<>(1, 10);
        p.setRecords(List.of(v));
        p.setTotal(1);

        // scanVersionRepository.lambdaQuery() 返回的链式调用
        var chainMock = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class, RETURNS_DEEP_STUBS);
        lenient().when(scanVersionRepository.lambdaQuery()).thenReturn(chainMock);
        lenient().when(chainMock.eq(any(), any())).thenReturn(chainMock);
        lenient().when(chainMock.orderByDesc(any(com.baomidou.mybatisplus.core.toolkit.support.SFunction.class))).thenReturn(chainMock);
        lenient().when(chainMock.page(any(Page.class))).thenReturn(p);

        var result = graphQueryService.getScanVersions("p1", 1, 10);

        assertEquals(1, result.getTotal());
        Map<String, Object> item = result.getList().get(0);
        assertEquals("v-1", item.get("id"));
        assertEquals(100L, item.get("nodeCount"));
        assertEquals(200L, item.get("edgeCount"));
        assertEquals(50L, item.get("factCount"));
        assertEquals(5, item.get("taskCount"));
        // 终态版本不应触发实时聚合查询
        verify(scanTaskRepository, never()).lambdaQuery();
    }
}
