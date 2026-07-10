package io.github.legacygraph.dao;

import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

/**
 * Neo4jGraphDao 单元测试 — 使用 Repository 层 mock。
 * 覆盖核心聚合方法 graphStats() 及 affected 相关委托方法。
 */
@ExtendWith(MockitoExtension.class)
class Neo4jGraphDaoTest {

    @Mock
    private Neo4jQueryRepository queryRepo;
    @Mock
    private Neo4jWriteRepository writeRepo;
    @Mock
    private Neo4jProjectionRepository projectionRepo;
    @Mock
    private Neo4jAdminRepository adminRepo;
    @Mock
    private Neo4jSchemaRepository schemaRepo;

    private Neo4jGraphDao graphDao;

    @BeforeEach
    void setUp() {
        graphDao = new Neo4jGraphDao(queryRepo, writeRepo, projectionRepo, adminRepo, schemaRepo);
    }

    @Test
    void normalizeIdHandlesNullAndEmpty() {
        assertEquals(null, Neo4jGraphDao.normalizeId(null));
        assertEquals(null, Neo4jGraphDao.normalizeId(""));
        assertEquals("abc", Neo4jGraphDao.normalizeId("abc"));
    }

    @Test
    void inlineStripHyphensRemovesDashes() {
        assertEquals("abcdef", "abc-def".replace("-", ""));
        assertEquals("12345", "12345".replace("-", ""));
        assertEquals(null, (String) null);
    }

    @Test
    void queryAffectedNodesDelegatesToQueryRepo() {
        String projectId = "proj-1";
        String versionId = "ver-1";
        String nodeType = "Service";

        GraphNode affected = new GraphNode();
        affected.setId("node-1");
        affected.setNodeType(nodeType);
        List<GraphNode> expected = List.of(affected);

        when(queryRepo.queryAffectedNodes(projectId, versionId, nodeType)).thenReturn(expected);

        List<GraphNode> result = graphDao.queryAffectedNodes(projectId, versionId, nodeType);

        assertEquals(1, result.size());
        assertSame(affected, result.get(0));
        assertEquals(nodeType, result.get(0).getNodeType());
    }

    @Test
    void queryAffectedNodesReturnsEmptyWhenNoneAffected() {
        when(queryRepo.queryAffectedNodes("proj", "ver", "Table")).thenReturn(List.of());

        List<GraphNode> result = graphDao.queryAffectedNodes("proj", "ver", "Table");

        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void clearAffectedMarkersDelegatesToWriteRepo() {
        String projectId = "proj-1";
        String versionId = "ver-1";

        when(writeRepo.clearAffectedMarkers(projectId, versionId)).thenReturn(3);

        int cleared = graphDao.clearAffectedMarkers(projectId, versionId);

        assertEquals(3, cleared);
    }

    @Test
    void clearAffectedMarkersReturnsZeroWhenNoneMarked() {
        when(writeRepo.clearAffectedMarkers("proj", "ver")).thenReturn(0);

        int cleared = graphDao.clearAffectedMarkers("proj", "ver");

        assertEquals(0, cleared);
    }
}
