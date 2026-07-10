package io.github.legacygraph.service.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.CodeRepoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * GraphQualityAssessor 单元测试 — 验证图谱质量评估逻辑与报告生成。
 *
 * <p>测试场景：
 * <ul>
 *   <li>正常数据：节点/边类型齐全，约束通过 → 报告包含完整章节</li>
 *   <li>空图谱：无节点无边 → 报告正确处理零值</li>
 *   <li>约束违反：存在缺失边的节点 → 报告体现违反数和建议</li>
 *   <li>关键类型缺失：Table 数量为 0 → 报告标记缺失状态</li>
 * </ul>
 *
 * <p>使用 {@code @TempDir} 作为回退报告目录，mock Neo4jGraphDao 返回测试数据。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GraphQualityAssessor 图谱质量评估测试")
class GraphQualityAssessorTest {

    @Mock
    private Neo4jGraphDao graphDao;

    @Mock
    private CodeRepoRepository codeRepoRepository;

    @TempDir
    Path tempDir;

    private GraphQualityAssessor assessor;

    @BeforeEach
    void setUp() {
        assessor = new GraphQualityAssessor(graphDao, codeRepoRepository);
        // 注入回退目录为临时目录，避免依赖 user.home
        ReflectionTestUtils.setField(assessor, "fallbackReportRoot", tempDir.toString());
        // codeRepoRepository 返回空列表 → 使用回退目录
        when(codeRepoRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of());
    }

    // ========================================================
    // 场景 1：正常数据 — 节点/边类型齐全，约束通过
    // ========================================================

    @Test
    @DisplayName("正常数据应生成包含完整章节的质量报告")
    void shouldGenerateFullReportWithNormalData() throws Exception {
        // given: 版本统计 100 节点 200 边
        when(graphDao.versionGraphStats(eq("proj-1"), eq("v1")))
                .thenReturn(Map.of("totalNodes", 100L, "totalEdges", 200L));
        // 节点类型分布
        when(graphDao.nodeTypeDistribution(eq("proj-1"), eq("v1")))
                .thenReturn(List.of(
                        Map.of("nodeType", "Table", "cnt", 20L),
                        Map.of("nodeType", "Column", "cnt", 50L),
                        Map.of("nodeType", "Service", "cnt", 10L),
                        Map.of("nodeType", "Controller", "cnt", 5L),
                        Map.of("nodeType", "ApiEndpoint", "cnt", 15L),
                        Map.of("nodeType", "Method", "cnt", 40L)
                ));
        // 边类型分布
        when(graphDao.edgeTypeDistribution(eq("proj-1"), eq("v1")))
                .thenReturn(List.of(
                        Map.of("edgeType", "CONTAINS", "cnt", 80L),
                        Map.of("edgeType", "CALLS", "cnt", 50L),
                        Map.of("edgeType", "READS", "cnt", 40L),
                        Map.of("edgeType", "WRITES", "cnt", 30L)
                ));
        // 连通性：5 个孤立节点
        when(graphDao.countIsolatedNodes(eq("proj-1"), eq("v1"))).thenReturn(5L);
        // 平均连通度 3.5
        when(graphDao.averageNodeDegree(eq("proj-1"), eq("v1"))).thenReturn(3.5);
        // 约束校验全部通过
        when(graphDao.countNodesWithoutEdgeTypes(eq("proj-1"), eq("v1"), any(), any()))
                .thenReturn(0L);
        // 准确性抽样：无数据
        when(graphDao.sampleEdgesForAccuracy(eq("proj-1"), eq("v1"), eq(100)))
                .thenReturn(List.of());

        // when
        assessor.assessAndReport("proj-1", "v1");

        // then: 报告文件已生成
        Path reportFile = tempDir.resolve("proj-1").resolve("docs/legacygraph").resolve("graph-quality-report.md");
        assertThat(reportFile).exists();
        String content = Files.readString(reportFile);

        // 概览
        assertThat(content).contains("# 图谱质量评估报告");
        assertThat(content).contains("项目ID: proj-1");
        assertThat(content).contains("扫描版本: v1");
        assertThat(content).contains("总节点数: 100");
        assertThat(content).contains("总边数: 200");
        assertThat(content).contains("边/节点比: 2.00");

        // 完整性 — 节点类型分布
        assertThat(content).contains("## 2. 完整性");
        assertThat(content).contains("| Table | 20 |");
        assertThat(content).contains("| Column | 50 |");
        assertThat(content).contains("| Service | 10 |");

        // 完整性 — 边类型分布
        assertThat(content).contains("| CONTAINS | 80 |");
        assertThat(content).contains("| CALLS | 50 |");

        // 连通性
        assertThat(content).contains("## 3. 连通性");
        assertThat(content).contains("孤立节点数: 5");
        assertThat(content).contains("平均连通度: 3.50");
        assertThat(content).contains("连通性评级: 良好");

        // 一致性
        assertThat(content).contains("## 4. 一致性");
        assertThat(content).contains("✓ 通过");

        // 改进建议
        assertThat(content).contains("## 6. 改进建议");
        assertThat(content).contains("图谱质量良好");
    }

    // ========================================================
    // 场景 2：空图谱 — 无节点无边
    // ========================================================

    @Test
    @DisplayName("空图谱应正确处理零值并标记关键类型缺失")
    void shouldHandleEmptyGraph() throws Exception {
        // given: 无节点无边
        when(graphDao.versionGraphStats(eq("proj-2"), eq("v2")))
                .thenReturn(Map.of("totalNodes", 0L, "totalEdges", 0L));
        when(graphDao.nodeTypeDistribution(eq("proj-2"), eq("v2")))
                .thenReturn(List.of());
        when(graphDao.edgeTypeDistribution(eq("proj-2"), eq("v2")))
                .thenReturn(List.of());
        when(graphDao.countIsolatedNodes(eq("proj-2"), eq("v2"))).thenReturn(0L);
        when(graphDao.averageNodeDegree(eq("proj-2"), eq("v2"))).thenReturn(0.0);
        when(graphDao.countNodesWithoutEdgeTypes(eq("proj-2"), eq("v2"), any(), any()))
                .thenReturn(0L);
        when(graphDao.sampleEdgesForAccuracy(eq("proj-2"), eq("v2"), eq(100)))
                .thenReturn(List.of());

        // when
        assessor.assessAndReport("proj-2", "v2");

        // then
        Path reportFile = tempDir.resolve("proj-2").resolve("docs/legacygraph").resolve("graph-quality-report.md");
        assertThat(reportFile).exists();
        String content = Files.readString(reportFile);

        assertThat(content).contains("总节点数: 0");
        assertThat(content).contains("总边数: 0");
        assertThat(content).contains("边/节点比: 0.00");
        // 关键类型全部标记缺失
        assertThat(content).contains("| Table | 0 | ⚠️ 缺失 |");
        assertThat(content).contains("| Method | 0 | ⚠️ 缺失 |");
        assertThat(content).contains("| Service | 0 | ⚠️ 缺失 |");
        // 连通性评级为"差"
        assertThat(content).contains("连通性评级: 差");
        // 改进建议中应有关键类型缺失提示
        assertThat(content).contains("关键节点类型 Table 数量为 0");
    }

    // ========================================================
    // 场景 3：约束违反 — 存在缺失必需边的节点
    // ========================================================

    @Test
    @DisplayName("约束违反应在报告中体现违反数和改进建议")
    void shouldReportConstraintViolations() throws Exception {
        // given: 50 节点 30 边（边/节点比 < 1.0）
        when(graphDao.versionGraphStats(eq("proj-3"), eq("v3")))
                .thenReturn(Map.of("totalNodes", 50L, "totalEdges", 30L));
        when(graphDao.nodeTypeDistribution(eq("proj-3"), eq("v3")))
                .thenReturn(List.of(
                        Map.of("nodeType", "Table", "cnt", 10L),
                        Map.of("nodeType", "Method", "cnt", 20L),
                        Map.of("nodeType", "ApiEndpoint", "cnt", 5L)
                ));
        when(graphDao.edgeTypeDistribution(eq("proj-3"), eq("v3")))
                .thenReturn(List.of(Map.of("edgeType", "CALLS", "cnt", 30L)));
        // 10 个孤立节点（20%）
        when(graphDao.countIsolatedNodes(eq("proj-3"), eq("v3"))).thenReturn(10L);
        when(graphDao.averageNodeDegree(eq("proj-3"), eq("v3"))).thenReturn(0.8);

        // 约束违反：Method 有 5 个违反，ApiEndpoint 有 2 个违反
        when(graphDao.countNodesWithoutEdgeTypes(eq("proj-3"), eq("v3"), eq("Method"), any()))
                .thenReturn(5L);
        when(graphDao.countNodesWithoutEdgeTypes(eq("proj-3"), eq("v3"), eq("Column"), any()))
                .thenReturn(0L);
        when(graphDao.countNodesWithoutEdgeTypes(eq("proj-3"), eq("v3"), eq("ApiEndpoint"), any()))
                .thenReturn(2L);
        when(graphDao.countNodesWithoutEdgeTypes(eq("proj-3"), eq("v3"), eq("SqlStatement"), any()))
                .thenReturn(0L);
        when(graphDao.sampleEdgesForAccuracy(eq("proj-3"), eq("v3"), eq(100)))
                .thenReturn(List.of());

        // when
        assessor.assessAndReport("proj-3", "v3");

        // then
        Path reportFile = tempDir.resolve("proj-3").resolve("docs/legacygraph").resolve("graph-quality-report.md");
        assertThat(reportFile).exists();
        String content = Files.readString(reportFile);

        // 边/节点比偏低
        assertThat(content).contains("边/节点比: 0.60");
        // 关键类型缺失（Column/Service/Controller 缺失）
        assertThat(content).contains("| Column | 0 | ⚠️ 缺失 |");
        assertThat(content).contains("| Service | 0 | ⚠️ 缺失 |");
        assertThat(content).contains("| Controller | 0 | ⚠️ 缺失 |");
        // 连通性评级
        assertThat(content).contains("孤立节点数: 10");
        assertThat(content).contains("连通性评级: 差");
        // 约束违反
        assertThat(content).contains("⚠️ 5 个违反");
        assertThat(content).contains("⚠️ 2 个违反");
        // 改进建议
        assertThat(content).contains("边/节点比仅为 0.60");
        assertThat(content).contains("关键节点类型 Column 数量为 0");
        assertThat(content).contains("孤立节点比例 20.0%");
        assertThat(content).contains("平均连通度 0.80 偏低");
    }

    // ========================================================
    // 场景 4：连通性评级边界 — 一般
    // ========================================================

    @Test
    @DisplayName("孤立率 10% 且平均连通度 1.5 应评级为一般")
    void shouldGradeAsFair() throws Exception {
        // given: 100 节点 120 边
        when(graphDao.versionGraphStats(eq("proj-4"), eq("v4")))
                .thenReturn(Map.of("totalNodes", 100L, "totalEdges", 120L));
        when(graphDao.nodeTypeDistribution(eq("proj-4"), eq("v4")))
                .thenReturn(List.of(
                        Map.of("nodeType", "Table", "cnt", 10L),
                        Map.of("nodeType", "Column", "cnt", 20L),
                        Map.of("nodeType", "Service", "cnt", 10L),
                        Map.of("nodeType", "Controller", "cnt", 5L),
                        Map.of("nodeType", "ApiEndpoint", "cnt", 10L),
                        Map.of("nodeType", "Method", "cnt", 20L)
                ));
        when(graphDao.edgeTypeDistribution(eq("proj-4"), eq("v4")))
                .thenReturn(List.of(Map.of("edgeType", "CONTAINS", "cnt", 120L)));
        // 10 个孤立节点（10%）
        when(graphDao.countIsolatedNodes(eq("proj-4"), eq("v4"))).thenReturn(10L);
        when(graphDao.averageNodeDegree(eq("proj-4"), eq("v4"))).thenReturn(1.5);
        when(graphDao.countNodesWithoutEdgeTypes(eq("proj-4"), eq("v4"), any(), any()))
                .thenReturn(0L);
        when(graphDao.sampleEdgesForAccuracy(eq("proj-4"), eq("v4"), eq(100)))
                .thenReturn(List.of());

        // when
        assessor.assessAndReport("proj-4", "v4");

        // then
        Path reportFile = tempDir.resolve("proj-4").resolve("docs/legacygraph").resolve("graph-quality-report.md");
        assertThat(reportFile).exists();
        String content = Files.readString(reportFile);

        assertThat(content).contains("连通性评级: 一般");
    }

    // ========================================================
    // 场景 5：准确性评估 — 抽样边包含有效和无效边
    // ========================================================

    @Test
    @DisplayName("准确性评估应在报告中体现抽样数和准确率")
    void shouldReportAccuracyMetrics() throws Exception {
        // given: 100 节点 200 边
        when(graphDao.versionGraphStats(eq("proj-5"), eq("v5")))
                .thenReturn(Map.of("totalNodes", 100L, "totalEdges", 200L));
        when(graphDao.nodeTypeDistribution(eq("proj-5"), eq("v5")))
                .thenReturn(List.of(
                        Map.of("nodeType", "Table", "cnt", 20L),
                        Map.of("nodeType", "Column", "cnt", 50L),
                        Map.of("nodeType", "Service", "cnt", 10L),
                        Map.of("nodeType", "Controller", "cnt", 5L),
                        Map.of("nodeType", "ApiEndpoint", "cnt", 15L),
                        Map.of("nodeType", "Method", "cnt", 40L)
                ));
        when(graphDao.edgeTypeDistribution(eq("proj-5"), eq("v5")))
                .thenReturn(List.of(Map.of("edgeType", "CONTAINS", "cnt", 200L)));
        when(graphDao.countIsolatedNodes(eq("proj-5"), eq("v5"))).thenReturn(5L);
        when(graphDao.averageNodeDegree(eq("proj-5"), eq("v5"))).thenReturn(3.5);
        when(graphDao.countNodesWithoutEdgeTypes(eq("proj-5"), eq("v5"), any(), any()))
                .thenReturn(0L);

        // 准确性抽样：10 条边，8 条有效，2 条无效（端点为空字符串）
        when(graphDao.sampleEdgesForAccuracy(eq("proj-5"), eq("v5"), eq(100)))
                .thenReturn(List.of(
                        Map.<String, Object>of("fromNodeId", "n1", "toNodeId", "n2", "edgeType", "CONTAINS",
                                "fromNodeType", "Table", "toNodeType", "Column"),
                        Map.<String, Object>of("fromNodeId", "n3", "toNodeId", "n4", "edgeType", "CALLS",
                                "fromNodeType", "Method", "toNodeType", "Method"),
                        Map.<String, Object>of("fromNodeId", "n5", "toNodeId", "n6", "edgeType", "READS",
                                "fromNodeType", "SqlStatement", "toNodeType", "Table"),
                        Map.<String, Object>of("fromNodeId", "n7", "toNodeId", "n8", "edgeType", "WRITES",
                                "fromNodeType", "SqlStatement", "toNodeType", "Table"),
                        Map.<String, Object>of("fromNodeId", "n9", "toNodeId", "n10", "edgeType", "BELONGS_TO",
                                "fromNodeType", "Method", "toNodeType", "Class"),
                        Map.<String, Object>of("fromNodeId", "n11", "toNodeId", "n12", "edgeType", "HANDLED_BY",
                                "fromNodeType", "ApiEndpoint", "toNodeType", "Controller"),
                        Map.<String, Object>of("fromNodeId", "n13", "toNodeId", "n14", "edgeType", "HAS_COLUMN",
                                "fromNodeType", "Table", "toNodeType", "Column"),
                        Map.<String, Object>of("fromNodeId", "n15", "toNodeId", "n16", "edgeType", "CONTAINS",
                                "fromNodeType", "Service", "toNodeType", "Method"),
                        // 无效边：toNodeId 为空字符串
                        Map.<String, Object>of("fromNodeId", "n17", "toNodeId", "", "edgeType", "CALLS",
                                "fromNodeType", "Method", "toNodeType", "Method"),
                        // 无效边：fromNodeId 为空字符串
                        Map.<String, Object>of("fromNodeId", "", "toNodeId", "n18", "edgeType", "READS",
                                "fromNodeType", "SqlStatement", "toNodeType", "Table")
                ));

        // when
        assessor.assessAndReport("proj-5", "v5");

        // then
        Path reportFile = tempDir.resolve("proj-5").resolve("docs/legacygraph").resolve("graph-quality-report.md");
        assertThat(reportFile).exists();
        String content = Files.readString(reportFile);

        // 准确性章节
        assertThat(content).contains("## 5. 准确性");
        assertThat(content).contains("抽样数: 10");
        assertThat(content).contains("有效边数: 8");
        assertThat(content).contains("准确率: 80.0%");
        // 准确率 < 90% 应有建议
        assertThat(content).contains("准确率仅为 80.0%");
        // 改进建议章节
        assertThat(content).contains("## 6. 改进建议");
    }
}
