package io.github.legacygraph.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.FactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JavaMemberCallResolverTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private FactRepository factRepository;

    private JavaMemberCallResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new JavaMemberCallResolver(neo4jGraphDao, factRepository, new ObjectMapper());
    }

    // ===== helpers =====

    private GraphNode classNode(String fqn, NodeType type) {
        GraphNode n = new GraphNode();
        n.setId("id-" + fqn);
        n.setNodeType(type.name());
        n.setNodeKey(fqn);
        n.setClassName(fqn);
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        n.setNodeName(simple);
        return n;
    }

    private GraphNode methodNode(String fqn, String methodWithSig) {
        GraphNode m = new GraphNode();
        String key = fqn + "." + methodWithSig;
        m.setId("id-" + key);
        m.setNodeType(NodeType.Method.name());
        m.setNodeKey(key);
        m.setClassName(fqn);
        return m;
    }

    private Fact callFact(String callerClass, String callerMethodSig, String calledMethodSig,
                          String targetClass, String calledMethod) throws Exception {
        String callerMethod = callerMethodSig != null && callerMethodSig.contains("(")
                ? callerMethodSig.substring(0, callerMethodSig.indexOf('(')) : null;
        // 字段顺序：callerClass, callerMethod, calledMethod, targetClass, targetMethod, sourcePath, lineNumber, callerMethodSignature, calledMethodSignature
        JavaMemberCallResolver.CallRelationDto dto = new JavaMemberCallResolver.CallRelationDto(
                callerClass, callerMethod, calledMethod, targetClass, calledMethod,
                null, null, callerMethodSig, calledMethodSig, null, null);
        Fact f = new Fact();
        f.setId("fact-" + callerClass + "-" + calledMethod);
        f.setFactType("SERVICE_CALL");
        f.setVersionId("v1");
        f.setNormalizedData(new ObjectMapper().writeValueAsString(dto));
        return f;
    }

    /** 把若干类/方法节点按 NodeType 分组，供 queryNodes 返回。 */
    private void stubQueryNodes(List<GraphNode> allNodes) {
        when(neo4jGraphDao.queryNodes(eq("p"), eq("v1"), anyString(), isNull(), isNull(), isNull(), eq(0)))
                .thenAnswer(inv -> {
                    String type = inv.getArgument(2);
                    List<GraphNode> filtered = new ArrayList<>();
                    for (GraphNode n : allNodes) {
                        if (type.equals(n.getNodeType())) {
                            filtered.add(n);
                        }
                    }
                    return filtered;
                });
    }

    // ===== tests =====

    @Test
    void happyPath_methodLevelCallEdge() throws Exception {
        // 一个 OrderService 调 orderMapper.insert(dto)：targetClass=OrderMapper（简单名），
        // 全局有 com.x.OrderMapper 类节点 + insert 方法节点 → 解析出方法级 CALLS 边
        GraphNode svc = classNode("com.x.OrderService", NodeType.Service);
        GraphNode mapper = classNode("com.x.OrderMapper", NodeType.Mapper);
        GraphNode callerMethod = methodNode("com.x.OrderService", "createOrder(Object)");
        GraphNode targetMethod = methodNode("com.x.OrderMapper", "insert(Object)");
        stubQueryNodes(List.of(svc, mapper, callerMethod, targetMethod));

        when(neo4jGraphDao.queryEdges(any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(neo4jGraphDao.mergeEdgesBatch(anyList())).thenAnswer(inv -> ((List<GraphEdge>) inv.getArgument(0)).size());
        when(factRepository.selectList(any())).thenReturn(List.of(
                callFact("com.x.OrderService", "createOrder(Object)", "insert(Object)", "OrderMapper", "insert")));

        int merged = resolver.resolveMemberCalls("p", "v1");

        assertEquals(1, merged);
        ArgumentCaptor<List<GraphEdge>> captor = ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(captor.capture());
        List<GraphEdge> edges = captor.getValue();
        assertEquals(1, edges.size());
        GraphEdge e = edges.get(0);
        assertEquals(EdgeType.CALLS.name(), e.getEdgeType());
        assertEquals("id-com.x.OrderService.createOrder(Object)", e.getFromNodeId());
        assertEquals("id-com.x.OrderMapper.insert(Object)", e.getToNodeId());
        assertEquals("com.x.OrderService.createOrder(Object)->calls->com.x.OrderMapper.insert(Object)", e.getEdgeKey());
    }

    @Test
    void godNode_ambiguousSimpleName_skips() throws Exception {
        // L-08: 两个同名 OrderMapper 类（不同包）→ 不再跳过，
        // 建边到首个候选（com.a.OrderMapper），标记 PENDING_CONFIRM（confidence=0.7）
        GraphNode svc = classNode("com.x.OrderService", NodeType.Service);
        GraphNode mapper1 = classNode("com.a.OrderMapper", NodeType.Mapper);
        GraphNode mapper2 = classNode("com.b.OrderMapper", NodeType.Mapper);
        stubQueryNodes(List.of(svc, mapper1, mapper2));
        when(neo4jGraphDao.queryEdges(any(), any(), any(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(neo4jGraphDao.mergeEdgesBatch(anyList())).thenAnswer(inv -> ((List<GraphEdge>) inv.getArgument(0)).size());
        when(factRepository.selectList(any())).thenReturn(List.of(
                callFact("com.x.OrderService", "createOrder()", "insert()", "OrderMapper", "insert")));

        int merged = resolver.resolveMemberCalls("p", "v1");

        // L-08: 歧义匹配建 1 条边，状态 PENDING_CONFIRM，连接到首个候选 com.a.OrderMapper
        assertEquals(1, merged);
        ArgumentCaptor<List<GraphEdge>> captor = ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao, times(1)).mergeEdgesBatch(captor.capture());
        GraphEdge edge = captor.getValue().get(0);
        assertEquals("id-com.x.OrderService", edge.getFromNodeId());
        assertEquals("id-com.a.OrderMapper", edge.getToNodeId());
        assertEquals("PENDING_CONFIRM", edge.getStatus());
    }

    @Test
    void overload_fallsBackToClassLevel() throws Exception {
        // OrderMapper 有 insert(String) + insert(Long) 两个重载 → 方法级 god-node 跳过，回退类级边
        GraphNode svc = classNode("com.x.OrderService", NodeType.Service);
        GraphNode mapper = classNode("com.x.OrderMapper", NodeType.Mapper);
        GraphNode m1 = methodNode("com.x.OrderMapper", "insert(String)");
        GraphNode m2 = methodNode("com.x.OrderMapper", "insert(Long)");
        // caller method 走 methodIndex（不 stub findNode 精确路径）
        stubQueryNodes(List.of(svc, mapper, m1, m2));
        when(neo4jGraphDao.queryEdges(any(), any(), any(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(neo4jGraphDao.mergeEdgesBatch(anyList())).thenAnswer(inv -> ((List<GraphEdge>) inv.getArgument(0)).size());
        when(factRepository.selectList(any())).thenReturn(List.of(
                callFact("com.x.OrderService", "createOrder()", "insert()", "OrderMapper", "insert")));

        int merged = resolver.resolveMemberCalls("p", "v1");

        assertEquals(1, merged);
        ArgumentCaptor<List<GraphEdge>> captor = ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(captor.capture());
        // 回退到类级：toNode = mapper 类节点
        assertEquals("id-com.x.OrderMapper", captor.getValue().get(0).getToNodeId());
    }

    @Test
    void nullTargetClass_skips() throws Exception {
        stubQueryNodes(List.of());
        when(neo4jGraphDao.queryEdges(any(), any(), any(), any(), anyInt())).thenReturn(Collections.emptyList());
        // targetClass = null
        Fact f = callFact("com.x.OrderService", "createOrder()", "insert()", null, "insert");
        when(factRepository.selectList(any())).thenReturn(List.of(f));

        int merged = resolver.resolveMemberCalls("p", "v1");
        assertEquals(0, merged);
        verify(neo4jGraphDao, never()).mergeEdgesBatch(anyList());
    }

    @Test
    void dedupe_existingPairSkips() throws Exception {
        // 已存在 caller→target 的 CALLS 边 → 不重复建
        GraphNode svc = classNode("com.x.OrderService", NodeType.Service);
        GraphNode mapper = classNode("com.x.OrderMapper", NodeType.Mapper);
        GraphNode callerMethod = methodNode("com.x.OrderService", "createOrder(Object)");
        GraphNode targetMethod = methodNode("com.x.OrderMapper", "insert(Object)");
        stubQueryNodes(List.of(svc, mapper, callerMethod, targetMethod));

        // 已存在该 (fromId,toId) 边
        GraphEdge existing = new GraphEdge();
        existing.setFromNodeId(callerMethod.getId());
        existing.setToNodeId(targetMethod.getId());
        // 兜底：继承边查询（edgeType=null）返回空列表，避免 NPE
        when(neo4jGraphDao.queryEdges(any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        // 精确：CALLS 边查询返回已存在边，触发去重
        when(neo4jGraphDao.queryEdges(eq("p"), eq("v1"), eq(EdgeType.CALLS.name()), isNull(), eq(0)))
                .thenReturn(List.of(existing));
        when(factRepository.selectList(any())).thenReturn(List.of(
                callFact("com.x.OrderService", "createOrder(Object)", "insert(Object)", "OrderMapper", "insert")));

        int merged = resolver.resolveMemberCalls("p", "v1");

        assertEquals(0, merged);
        verify(neo4jGraphDao, never()).mergeEdgesBatch(anyList());
    }
}
