package io.github.legacygraph.service.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ReusableComponentMarker} 单元测试。
 * 验证：EXTENDS 入度统计、阈值过滤、reusable/extendedBy 标记、reuseType 推断。
 */
@ExtendWith(MockitoExtension.class)
class ReusableComponentMarkerTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    private ReusableComponentMarker marker;

    @BeforeEach
    void setUp() {
        marker = new ReusableComponentMarker(neo4jGraphDao, new ObjectMapper());
        // @Value 在单元测试中不生效，手动设置阈值为默认值 2
        ReflectionTestUtils.setField(marker, "reusableThreshold", 2);
    }

    @Test
    void mark_marksNodeWhenExtendsCountReachesThreshold() {
        // 2 个子类继承 1 个父类，达到阈值 2
        GraphEdge edge1 = new GraphEdge();
        edge1.setFromNodeId("child1");
        edge1.setToNodeId("parent");
        GraphEdge edge2 = new GraphEdge();
        edge2.setFromNodeId("child2");
        edge2.setToNodeId("parent");

        GraphNode parentNode = new GraphNode();
        parentNode.setId("parent");
        parentNode.setNodeName("BaseEntity");
        parentNode.setNodeType("Class");
        parentNode.setClassName("BaseEntity");

        GraphNode child1 = new GraphNode();
        child1.setId("child1");
        child1.setNodeName("UserEntity");
        child1.setClassName("UserEntity");

        GraphNode child2 = new GraphNode();
        child2.setId("child2");
        child2.setNodeName("OrderEntity");
        child2.setClassName("OrderEntity");

        when(neo4jGraphDao.queryEdges(eq("p1"), eq("v1"), eq("EXTENDS"), isNull(), anyInt()))
                .thenReturn(List.of(edge1, edge2));
        when(neo4jGraphDao.findNodeById("parent")).thenReturn(Optional.of(parentNode));
        when(neo4jGraphDao.findNodeById("child1")).thenReturn(Optional.of(child1));
        when(neo4jGraphDao.findNodeById("child2")).thenReturn(Optional.of(child2));

        int marked = marker.mark("p1", "v1");

        assertEquals(1, marked, "应标记 1 个可复用节点");
        ArgumentCaptor<GraphNode> nodeCaptor = ArgumentCaptor.forClass(GraphNode.class);
        verify(neo4jGraphDao).updateNode(nodeCaptor.capture());
        GraphNode updated = nodeCaptor.getValue();
        String props = updated.getProperties();
        assertTrue(props.contains("\"reusable\":true"), "properties 应包含 reusable=true");
        assertTrue(props.contains("\"usageCount\":2"), "properties 应包含 usageCount=2");
        assertTrue(props.contains("\"extendedBy\""), "properties 应包含 extendedBy 字段");
        assertTrue(props.contains("UserEntity"), "extendedBy 应包含子类名称 UserEntity");
        assertTrue(props.contains("OrderEntity"), "extendedBy 应包含子类名称 OrderEntity");
        assertTrue(props.contains("BASE_ENTITY"), "reuseType 应为 BASE_ENTITY（类名含 Base）");
    }

    @Test
    void mark_skipsNodeWhenExtendsCountBelowThreshold() {
        // 只有 1 个子类，低于阈值 2
        GraphEdge edge = new GraphEdge();
        edge.setFromNodeId("child1");
        edge.setToNodeId("parent");

        when(neo4jGraphDao.queryEdges(eq("p1"), eq("v1"), eq("EXTENDS"), isNull(), anyInt()))
                .thenReturn(List.of(edge));

        int marked = marker.mark("p1", "v1");

        assertEquals(0, marked, "低于阈值不应标记");
        verify(neo4jGraphDao, never()).updateNode(any());
    }

    @Test
    void mark_returnsZeroWhenNoExtendsEdges() {
        when(neo4jGraphDao.queryEdges(eq("p1"), eq("v1"), eq("EXTENDS"), isNull(), anyInt()))
                .thenReturn(List.of());

        int marked = marker.mark("p1", "v1");

        assertEquals(0, marked, "无 EXTENDS 边时应返回 0");
        verify(neo4jGraphDao, never()).updateNode(any());
    }

    @Test
    void mark_skipsWhenProjectIdOrVersionIdBlank() {
        assertEquals(0, marker.mark(null, "v1"));
        assertEquals(0, marker.mark("p1", null));
        assertEquals(0, marker.mark("", "v1"));
        assertEquals(0, marker.mark("p1", ""));
    }

    @Test
    void inferReuseType_classifiesByClassName() {
        assertEquals("BASE_ENTITY", marker.inferReuseType("BaseEntity"));
        assertEquals("BASE_ENTITY", marker.inferReuseType("AbstractController"));
        assertEquals("UTIL", marker.inferReuseType("StringUtils"));
        assertEquals("UTIL", marker.inferReuseType("DateHelper"));
        assertEquals("RESULT_WRAPPER", marker.inferReuseType("Result"));
        assertEquals("RESULT_WRAPPER", marker.inferReuseType("PageResponse"));
        assertEquals("CONFIG", marker.inferReuseType("AppConfig"));
        assertEquals("CONFIG", marker.inferReuseType("DataSourceProperties"));
        assertEquals("COMPONENT", marker.inferReuseType("UserService"));
        assertEquals("COMPONENT", marker.inferReuseType(null));
        assertEquals("COMPONENT", marker.inferReuseType(""));
        // 含包路径的类名应正确取简单名
        assertEquals("UTIL", marker.inferReuseType("com.example.StringUtils"));
    }

    @Test
    void mark_preservesExistingProperties() {
        GraphEdge edge1 = new GraphEdge();
        edge1.setFromNodeId("child1");
        edge1.setToNodeId("parent");
        GraphEdge edge2 = new GraphEdge();
        edge2.setFromNodeId("child2");
        edge2.setToNodeId("parent");

        GraphNode parentNode = new GraphNode();
        parentNode.setId("parent");
        parentNode.setNodeName("BaseController");
        parentNode.setNodeType("Class");
        parentNode.setClassName("BaseController");
        // 已有 properties 字段（如 extendedTypes）
        parentNode.setProperties("{\"extendedTypes\":[\"Serializable\"]}");

        GraphNode child1 = new GraphNode();
        child1.setId("child1");
        child1.setNodeName("UserController");
        child1.setClassName("UserController");
        GraphNode child2 = new GraphNode();
        child2.setId("child2");
        child2.setNodeName("OrderController");
        child2.setClassName("OrderController");

        when(neo4jGraphDao.queryEdges(eq("p1"), eq("v1"), eq("EXTENDS"), isNull(), anyInt()))
                .thenReturn(List.of(edge1, edge2));
        when(neo4jGraphDao.findNodeById("parent")).thenReturn(Optional.of(parentNode));
        when(neo4jGraphDao.findNodeById("child1")).thenReturn(Optional.of(child1));
        when(neo4jGraphDao.findNodeById("child2")).thenReturn(Optional.of(child2));

        marker.mark("p1", "v1");

        ArgumentCaptor<GraphNode> nodeCaptor = ArgumentCaptor.forClass(GraphNode.class);
        verify(neo4jGraphDao).updateNode(nodeCaptor.capture());
        String props = nodeCaptor.getValue().getProperties();
        // 原有字段应保留
        assertTrue(props.contains("extendedTypes"), "应保留原有 extendedTypes 字段");
        assertTrue(props.contains("Serializable"), "应保留原有 extendedTypes 值");
        // 新标记应追加
        assertTrue(props.contains("reusable"), "应追加 reusable 标记");
    }
}
