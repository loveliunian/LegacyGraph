package io.github.legacygraph.service.scan;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EdgeCompletionService 单元测试 — 验证传递闭包补全与规则校验补全逻辑。
 *
 * <p>mock 策略：通过 neo4jGraphDao.executeReadQuery 返回 List&lt;Map&gt; 模拟 Cypher 查询结果，
 * 不直接依赖 Neo4j Driver。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EdgeCompletionService 边补全服务测试")
class EdgeCompletionServiceTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    // ==================== 传递闭包补全 ====================

    @Test
    @DisplayName("传递闭包：发现间接依赖对时补建 DEPENDS_ON 边并标记 PENDING_CONFIRM/0.7/TRANSITIVE_CLOSURE")
    void transitiveClosureAddsDependsOnEdgesWithCorrectMarkers() {
        when(neo4jGraphDao.executeReadQuery(anyString(), any())).thenReturn(List.of(
                Map.of("fromId", "pkg-a-id", "toId", "pkg-c-id", "fromKey", "com.a", "toKey", "com.c")
        ));
        when(neo4jGraphDao.mergeEdge(any(GraphEdge.class))).thenAnswer(inv ->
                new Neo4jGraphDao.EdgeUpsert(inv.getArgument(0), true));

        EdgeCompletionService service = new EdgeCompletionService(neo4jGraphDao);

        int added = service.completeTransitiveClosure("p1", "v1");

        assertThat(added).isEqualTo(1);
        ArgumentCaptor<GraphEdge> edgeCaptor = ArgumentCaptor.forClass(GraphEdge.class);
        verify(neo4jGraphDao).mergeEdge(edgeCaptor.capture());
        GraphEdge edge = edgeCaptor.getValue();
        assertThat(edge.getEdgeType()).isEqualTo(EdgeType.DEPENDS_ON.name());
        assertThat(edge.getSourceType()).isEqualTo(SourceType.TRANSITIVE_CLOSURE.name());
        assertThat(edge.getStatus()).isEqualTo(NodeStatus.PENDING_CONFIRM.name());
        assertThat(edge.getConfidence()).isEqualByComparingTo(BigDecimal.valueOf(0.7));
        assertThat(edge.getFromNodeId()).isEqualTo("pkg-a-id");
        assertThat(edge.getToNodeId()).isEqualTo("pkg-c-id");
        assertThat(edge.getProjectId()).isEqualTo("p1");
        assertThat(edge.getEdgeKey()).isEqualTo("com.a->depends_on->com.c");
    }

    @Test
    @DisplayName("传递闭包：无间接依赖对时不补建任何边")
    void transitiveClosureAddsNothingWhenNoPairs() {
        when(neo4jGraphDao.executeReadQuery(anyString(), any())).thenReturn(List.of());

        EdgeCompletionService service = new EdgeCompletionService(neo4jGraphDao);

        int added = service.completeTransitiveClosure("p1", "v1");

        assertThat(added).isZero();
        verify(neo4jGraphDao, never()).mergeEdge(any(GraphEdge.class));
    }

    // ==================== 规则校验：异常标记 ====================

    @Test
    @DisplayName("规则校验：Table 缺 HAS_COLUMN 与 ApiEndpoint 缺 HANDLED_BY 记录为异常，不自动补全")
    void ruleValidationRecordsTableAndApiAnomaliesWithoutAutoFix() {
        // completeByRules 顺序：fixMissingBelongsTo(1次查询) -> detectTablesWithoutColumns(1次) -> detectApiEndpointsWithoutHandler(1次)
        when(neo4jGraphDao.executeReadQuery(anyString(), any()))
                .thenReturn(List.of()) // 类扫描：无缺失 BELONGS_TO 的类
                .thenReturn(List.of(Map.of("nodeId", "table-1", "nodeKey", "order"))) // Table 异常
                .thenReturn(List.of(Map.of("nodeId", "api-1", "nodeKey", "GET /orders"))); // ApiEndpoint 异常

        EdgeCompletionService service = new EdgeCompletionService(neo4jGraphDao);

        int fixed = service.completeByRules("p1", "v1");

        assertThat(fixed).isZero();
        verify(neo4jGraphDao, never()).mergeEdge(any(GraphEdge.class));
    }

    @Test
    @DisplayName("规则校验：completeAll 将 Table/ApiEndpoint 异常汇总到报告")
    void completeAllAggregatesAnomaliesIntoReport() {
        // completeAll 顺序：transitiveClosure(1) -> fixMissingBelongsTo(1) -> tables(1) -> apis(1)
        when(neo4jGraphDao.executeReadQuery(anyString(), any()))
                .thenReturn(List.of()) // 传递闭包：无数据
                .thenReturn(List.of()) // 类扫描：无数据
                .thenReturn(List.of(Map.of("nodeId", "t1", "nodeKey", "user"))) // Table 异常
                .thenReturn(List.of(Map.of("nodeId", "a1", "nodeKey", "POST /users"))); // ApiEndpoint 异常

        EdgeCompletionService service = new EdgeCompletionService(neo4jGraphDao);

        EdgeCompletionService.CompletionReport report = service.completeAll("p1", "v1");

        assertThat(report.getAnomalies()).hasSize(2);
        assertThat(report.getAnomalies())
                .extracting(EdgeCompletionService.Anomaly::type)
                .containsExactlyInAnyOrder("TABLE_NO_COLUMN", "API_NO_HANDLER");
        assertThat(report.getTransitiveEdgesAdded()).isZero();
        assertThat(report.getBelongsToFixed()).isZero();
    }

    // ==================== 规则校验：BELONGS_TO 自动补建 ====================

    @Test
    @DisplayName("规则校验：Class 缺 BELONGS_TO 且能解析包名时补建 BELONGS_TO 边")
    void ruleValidationFixesMissingBelongsTo() {
        // fixMissingBelongsTo: classScan 查询返回 1 个类 -> findPackageId 查询返回 1 个 pkgId
        // 然后 detectTablesWithoutColumns + detectApiEndpointsWithoutHandler 各 1 次查询
        when(neo4jGraphDao.executeReadQuery(anyString(), any()))
                .thenReturn(List.of(classRow("svc-1", "com.example.service.OrderService"))) // 类扫描
                .thenReturn(List.of(Map.of("pkgId", "pkg-1"))) // 包查询：找到 Package 节点
                .thenReturn(List.of()) // Table 扫描：无异常
                .thenReturn(List.of()); // ApiEndpoint 扫描：无异常

        when(neo4jGraphDao.mergeEdge(any(GraphEdge.class))).thenAnswer(inv ->
                new Neo4jGraphDao.EdgeUpsert(inv.getArgument(0), true));

        EdgeCompletionService service = new EdgeCompletionService(neo4jGraphDao);

        int fixed = service.completeByRules("p1", "v1");

        assertThat(fixed).isEqualTo(1);
        ArgumentCaptor<GraphEdge> edgeCaptor = ArgumentCaptor.forClass(GraphEdge.class);
        verify(neo4jGraphDao).mergeEdge(edgeCaptor.capture());
        GraphEdge edge = edgeCaptor.getValue();
        assertThat(edge.getEdgeType()).isEqualTo(EdgeType.BELONGS_TO.name());
        assertThat(edge.getSourceType()).isEqualTo(SourceType.TRANSITIVE_CLOSURE.name());
        assertThat(edge.getStatus()).isEqualTo(NodeStatus.PENDING_CONFIRM.name());
        assertThat(edge.getConfidence()).isEqualByComparingTo(BigDecimal.valueOf(0.7));
        assertThat(edge.getFromNodeId()).isEqualTo("svc-1");
        assertThat(edge.getToNodeId()).isEqualTo("pkg-1");
        assertThat(edge.getEdgeKey()).isEqualTo("com.example.service.OrderService->belongs_to->com.example.service");
    }

    @Test
    @DisplayName("规则校验：Class 缺 BELONGS_TO 但找不到 Package 节点时记录异常")
    void ruleValidationRecordsAnomalyWhenPackageNotFound() {
        // completeAll 顺序：transitiveClosure(1) -> classScan(1) -> findPackageId(1) -> tables(1) -> apis(1) = 5 次查询
        when(neo4jGraphDao.executeReadQuery(anyString(), any()))
                .thenReturn(List.of()) // 传递闭包：无数据
                .thenReturn(List.of(classRow("svc-2", "com.example.service.OrderService"))) // 类扫描
                .thenReturn(List.of()) // 包查询：无结果
                .thenReturn(List.of()) // Table 扫描：无异常
                .thenReturn(List.of()); // ApiEndpoint 扫描：无异常

        EdgeCompletionService service = new EdgeCompletionService(neo4jGraphDao);

        EdgeCompletionService.CompletionReport report = service.completeAll("p1", "v1");

        assertThat(report.getBelongsToFixed()).isZero();
        assertThat(report.getAnomalies())
                .extracting(EdgeCompletionService.Anomaly::type)
                .contains("PACKAGE_NOT_FOUND");
        verify(neo4jGraphDao, never()).mergeEdge(any(GraphEdge.class));
    }

    // ==================== 包名解析 ====================

    @Test
    @DisplayName("包名解析：FQN 正确解析为包名，简单类名/文件路径返回 null")
    void parsePackageFromNodeKeyExtractsPackage() {
        assertThat(EdgeCompletionService.parsePackageFromNodeKey("com.example.service.OrderService"))
                .isEqualTo("com.example.service");
        assertThat(EdgeCompletionService.parsePackageFromNodeKey("com.example.OrderController"))
                .isEqualTo("com.example");
        assertThat(EdgeCompletionService.parsePackageFromNodeKey("OrderService")).isNull();
        assertThat(EdgeCompletionService.parsePackageFromNodeKey("src/main/java/OrderService.java")).isNull();
        assertThat(EdgeCompletionService.parsePackageFromNodeKey(null)).isNull();
        assertThat(EdgeCompletionService.parsePackageFromNodeKey("")).isNull();
    }

    // ==================== 容错 ====================

    @Test
    @DisplayName("容错：传递闭包阶段异常不阻塞规则校验阶段")
    void completeAllDoesNotFailWhenTransitiveClosureThrows() {
        when(neo4jGraphDao.executeReadQuery(anyString(), any()))
                .thenThrow(new RuntimeException("neo4j connection refused")) // 传递闭包抛异常
                .thenReturn(List.of()) // 类扫描：无数据
                .thenReturn(List.of()) // Table 扫描：无异常
                .thenReturn(List.of()); // ApiEndpoint 扫描：无异常

        EdgeCompletionService service = new EdgeCompletionService(neo4jGraphDao);

        EdgeCompletionService.CompletionReport report = service.completeAll("p1", "v1");

        assertThat(report.getAnomalies())
                .extracting(EdgeCompletionService.Anomaly::type)
                .contains("TRANSTITIVE_CLOSURE_ERROR");
        assertThat(report.getBelongsToFixed()).isZero();
    }

    /** 构造类节点查询行（sourcePath 为 null，Map.of 不支持 null 值） */
    private static Map<String, Object> classRow(String nodeId, String nodeKey) {
        Map<String, Object> row = new HashMap<>();
        row.put("nodeId", nodeId);
        row.put("nodeKey", nodeKey);
        row.put("sourcePath", null);
        return row;
    }
}
