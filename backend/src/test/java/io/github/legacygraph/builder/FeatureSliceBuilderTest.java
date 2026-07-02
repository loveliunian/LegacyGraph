package io.github.legacygraph.builder;

import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.FeatureSlice;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FeatureSliceBuilder 单元测试。
 * 验证按 name/id 构建功能切片与缺失节点兜底。
 */
@ExtendWith(MockitoExtension.class)
class FeatureSliceBuilderTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @InjectMocks
    private FeatureSliceBuilder builder;

    /**
     * 测试 Feature 节点不存在时返回 missingSlice。
     * findFeatureNode 通过 queryNodes 查找（精确+模糊），均无结果时返回 missingSlice。
     */
    @Test
    void buildSlice_whenFeatureNotFound_returnsMissingSlice() {
        // 精确查找返回空
        when(neo4jGraphDao.queryNodes(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        FeatureSlice slice = builder.buildSlice("project-1", "v1", "订单创建");

        assertNotNull(slice);
        assertEquals("NO_FEATURE_NODE", slice.getStatus());
        assertEquals("UNCOVERED", slice.getCoverageStatus());
        assertEquals("HIGH", slice.getRiskLevel());
        assertEquals(BigDecimal.ZERO, slice.getConfidence());
        assertEquals("订单创建", slice.getFeatureName());
    }

    /**
     * 测试按 sliceId 构建时节点类型不匹配返回 missingSlice。
     */
    @Test
    void buildSliceById_wrongNodeType_returnsMissingSlice() {
        GraphNode node = new GraphNode();
        node.setId("wrong-1");
        node.setProjectId("project-1");
        node.setNodeType(NodeType.Method.name());
        when(neo4jGraphDao.findNodeById("wrong-1")).thenReturn(Optional.of(node));

        FeatureSlice slice = builder.buildSliceById("project-1", "wrong-1");

        assertNotNull(slice);
        assertEquals("NO_FEATURE_NODE", slice.getStatus());
    }

    /**
     * 测试节点不存在时 buildSliceById 返回 missingSlice。
     */
    @Test
    void buildSliceById_notFound_returnsMissingSlice() {
        when(neo4jGraphDao.findNodeById("non-existent")).thenReturn(Optional.empty());

        FeatureSlice slice = builder.buildSliceById("project-1", "non-existent");

        assertNotNull(slice);
        assertEquals("NO_FEATURE_NODE", slice.getStatus());
    }

    /**
     * 测试 buildSliceByFeatureName 别名方法正常委托。
     */
    @Test
    void buildSliceByFeatureName_delegatesToBuildSlice() {
        when(neo4jGraphDao.queryNodes(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        FeatureSlice result = builder.buildSliceByFeatureName("project-1", "v1", "用户管理");

        assertNotNull(result);
        assertEquals("用户管理", result.getFeatureName());
    }
}
