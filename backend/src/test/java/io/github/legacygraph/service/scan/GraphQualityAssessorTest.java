package io.github.legacygraph.service.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.EdgeEvidence;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.EdgeEvidenceRepository;
import io.github.legacygraph.repository.NodeEvidenceRepository;
import io.github.legacygraph.service.graph.GraphMergeService;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * GraphQualityAssessor 单元测试 — 验证重做后的 4 个准确率指标及报告生成。
 *
 * <p>测试场景：
 * <ul>
 *   <li>正常数据：节点/边类型齐全，指标健康 → 报告包含完整章节</li>
 *   <li>空图谱：无节点无边 → 准确率非 100%（显示 N/A），覆盖率 N/A</li>
 *   <li>悬空边检测：countDanglingEdges > 0 → 报告标注 ⚠️ 并给出建议</li>
 *   <li>证据覆盖率：countNodes=100, countDistinctNodeIds=80 → 覆盖率 80.0%</li>
 *   <li>置信度校准：按来源分桶统计 Claim 状态分布</li>
 *   <li>约束违反：存在缺失边的节点 → 报告体现违反数和建议</li>
 * </ul>
 *
 * <p>使用 {@code @TempDir} 作为回退报告目录，mock Neo4jGraphDao 及多个 Repository 返回测试数据。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GraphQualityAssessor 图谱质量评估测试")
class GraphQualityAssessorTest {

    @Mock
    private Neo4jGraphDao graphDao;

    @Mock
    private CodeRepoRepository codeRepoRepository;

    @Mock
    private NodeEvidenceRepository nodeEvidenceRepository;

    @Mock
    private EdgeEvidenceRepository edgeEvidenceRepository;

    @Mock
    private KnowledgeClaimService knowledgeClaimService;

    @Mock
    private GraphMergeService graphMergeService;

    @TempDir
    Path tempDir;

    private GraphQualityAssessor assessor;

    @BeforeEach
    void setUp() {
        assessor = new GraphQualityAssessor(graphDao, codeRepoRepository,
                nodeEvidenceRepository, edgeEvidenceRepository, knowledgeClaimService,
                graphMergeService);
        // 注入回退目录为临时目录，避免依赖 user.home
        ReflectionTestUtils.setField(assessor, "fallbackReportRoot", tempDir.toString());
        // codeRepoRepository 返回空列表 → 使用回退目录
        lenient().when(codeRepoRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of());
    }

    /**
     * 为指定项目/版本设置 lenient 默认 stub，返回全零/空数据。
     * 各测试可在此基础上覆盖特定 stub。
     */
    private void stubDefaults(String pid, String vid, long nodes, long edges) {
        lenient().when(graphDao.versionGraphStats(eq(pid), eq(vid)))
                .thenReturn(Map.of("totalNodes", nodes, "totalEdges", edges));
        lenient().when(graphDao.nodeTypeDistribution(eq(pid), eq(vid))).thenReturn(List.of());
        lenient().when(graphDao.edgeTypeDistribution(eq(pid), eq(vid))).thenReturn(List.of());
        lenient().when(graphDao.countIsolatedNodes(eq(pid), eq(vid))).thenReturn(0L);
        lenient().when(graphDao.averageNodeDegree(eq(pid), eq(vid))).thenReturn(0.0);
        lenient().when(graphDao.countNodesWithoutEdgeTypes(eq(pid), eq(vid), any(), any())).thenReturn(0L);
        lenient().when(graphDao.countDanglingEdges(eq(pid), eq(vid))).thenReturn(0L);
        lenient().when(graphDao.countDuplicateNodes(eq(pid), eq(vid))).thenReturn(0L);
        lenient().when(graphDao.sampleEdgesWithEvidence(eq(pid), eq(vid), anyInt())).thenReturn(List.of());
        lenient().when(nodeEvidenceRepository.countDistinctNodeIds(eq(pid), eq(vid))).thenReturn(0L);
        lenient().when(edgeEvidenceRepository.countDistinctEdgeIds(eq(pid), eq(vid))).thenReturn(0L);
        lenient().when(edgeEvidenceRepository.selectList(any())).thenReturn(List.of());
        lenient().when(knowledgeClaimService.countByStatus(eq(pid), eq(vid), any(), any())).thenReturn(0L);
    }

    private Path reportFile(String pid) {
        return tempDir.resolve(pid).resolve("docs/legacygraph").resolve("graph-quality-report.md");
    }

    // ========================================================
    // 场景 1：正常数据 — 指标健康，报告包含完整章节
    // ========================================================

    @Test
    @DisplayName("正常数据应生成包含完整章节的质量报告")
    void shouldGenerateFullReportWithNormalData() throws Exception {
        // given: 100 节点 200 边，类型齐全
        stubDefaults("proj-1", "v1", 100, 200);
        when(graphDao.nodeTypeDistribution(eq("proj-1"), eq("v1")))
                .thenReturn(List.of(
                        Map.of("nodeType", "Table", "cnt", 20L),
                        Map.of("nodeType", "Column", "cnt", 50L),
                        Map.of("nodeType", "Service", "cnt", 10L),
                        Map.of("nodeType", "Controller", "cnt", 5L),
                        Map.of("nodeType", "ApiEndpoint", "cnt", 15L),
                        Map.of("nodeType", "Method", "cnt", 40L)
                ));
        when(graphDao.edgeTypeDistribution(eq("proj-1"), eq("v1")))
                .thenReturn(List.of(Map.of("edgeType", "CONTAINS", "cnt", 200L)));
        when(graphDao.countIsolatedNodes(eq("proj-1"), eq("v1"))).thenReturn(5L);
        when(graphDao.averageNodeDegree(eq("proj-1"), eq("v1"))).thenReturn(3.5);
        // 证据覆盖率：80/100 节点、100/200 边有证据
        when(nodeEvidenceRepository.countDistinctNodeIds(eq("proj-1"), eq("v1"))).thenReturn(80L);
        when(edgeEvidenceRepository.countDistinctEdgeIds(eq("proj-1"), eq("v1"))).thenReturn(100L);

        // when
        assessor.assessAndReport("proj-1", "v1");

        // then
        String content = Files.readString(reportFile("proj-1"));
        assertThat(content).contains("# 图谱质量评估报告");
        assertThat(content).contains("总节点数: 100");
        assertThat(content).contains("总边数: 200");
        assertThat(content).contains("边/节点比: 2.00");
        // 连通性
        assertThat(content).contains("## 3. 连通性");
        assertThat(content).contains("连通性评级: 良好");
        // 一致性
        assertThat(content).contains("## 4. 一致性");
        assertThat(content).contains("✓ 通过");
        // 结构完整性
        assertThat(content).contains("## 5. 结构完整性");
        assertThat(content).contains("悬空边数: 0");
        // 抽样边证据支撑率（无抽样边 → N/A）；不能命名为事实准确率。
        assertThat(content).contains("## 6. 抽样边证据支撑率");
        assertThat(content).contains("N/A");
        // 证据覆盖率
        assertThat(content).contains("## 7. 证据覆盖率");
        assertThat(content).contains("80/100 (80.0%)");
        assertThat(content).contains("100/200 (50.0%)");
        // 来源状态分布，不冒充统计校准。
        assertThat(content).contains("## 8. 来源状态分布");
        assertThat(content).contains("| CODE |");
        // 改进建议
        assertThat(content).contains("## 9. 改进建议");
        assertThat(content).contains("图谱质量良好");
    }

    // ========================================================
    // 场景 2：空图谱 — 准确率非 100%
    // ========================================================

    @Test
    @DisplayName("空图谱准确率应显示 N/A 而非 100%")
    void emptyGraphAccuracyShouldNotBe100Percent() throws Exception {
        // given: 无节点无边
        stubDefaults("proj-2", "v2", 0, 0);

        // when
        assessor.assessAndReport("proj-2", "v2");

        // then
        String content = Files.readString(reportFile("proj-2"));
        assertThat(content).contains("总节点数: 0");
        assertThat(content).contains("总边数: 0");
        // 证据支撑率应为 N/A，而非 100%
        assertThat(content).contains("## 6. 抽样边证据支撑率");
        assertThat(content).contains("证据支撑率: N/A");
        assertThat(content).doesNotContain("证据支撑率: 100.0%");
        // 证据覆盖率应为 N/A
        assertThat(content).contains("## 7. 证据覆盖率");
        assertThat(content).contains("节点覆盖率: N/A（无节点）");
        assertThat(content).contains("边覆盖率: N/A（无边）");
    }

    // ========================================================
    // 场景 3：悬空边检测 — countDanglingEdges > 0
    // ========================================================

    @Test
    @DisplayName("悬空边应在报告中标注 ⚠️ 并生成改进建议")
    void shouldDetectDanglingEdges() throws Exception {
        // given: 50 节点 80 边，5 条悬空边
        stubDefaults("proj-3", "v3", 50, 80);
        when(graphDao.nodeTypeDistribution(eq("proj-3"), eq("v3")))
                .thenReturn(List.of(
                        Map.of("nodeType", "Table", "cnt", 10L),
                        Map.of("nodeType", "Column", "cnt", 20L),
                        Map.of("nodeType", "Service", "cnt", 5L),
                        Map.of("nodeType", "Controller", "cnt", 3L),
                        Map.of("nodeType", "ApiEndpoint", "cnt", 5L),
                        Map.of("nodeType", "Method", "cnt", 7L)
                ));
        when(graphDao.countIsolatedNodes(eq("proj-3"), eq("v3"))).thenReturn(3L);
        when(graphDao.averageNodeDegree(eq("proj-3"), eq("v3"))).thenReturn(2.5);
        when(graphDao.countDanglingEdges(eq("proj-3"), eq("v3"))).thenReturn(5L);
        // 证据覆盖率良好
        when(nodeEvidenceRepository.countDistinctNodeIds(eq("proj-3"), eq("v3"))).thenReturn(40L);
        when(edgeEvidenceRepository.countDistinctEdgeIds(eq("proj-3"), eq("v3"))).thenReturn(50L);

        // when
        assessor.assessAndReport("proj-3", "v3");

        // then
        String content = Files.readString(reportFile("proj-3"));
        assertThat(content).contains("## 5. 结构完整性");
        assertThat(content).contains("悬空边数: 5");
        assertThat(content).contains("⚠️ 5 条悬空边");
        // 改进建议中应提到悬空边
        assertThat(content).contains("存在 5 条悬空边");
    }

    // ========================================================
    // 场景 4：证据覆盖率数值验证 — 80/100 = 80.0%
    // ========================================================

    @Test
    @DisplayName("证据覆盖率应正确计算节点和边的覆盖率")
    void shouldVerifyEvidenceCoverageRates() throws Exception {
        // given: 100 节点 200 边，80 节点有证据，60 边有证据
        stubDefaults("proj-4", "v4", 100, 200);
        when(graphDao.nodeTypeDistribution(eq("proj-4"), eq("v4")))
                .thenReturn(List.of(
                        Map.of("nodeType", "Table", "cnt", 20L),
                        Map.of("nodeType", "Column", "cnt", 50L),
                        Map.of("nodeType", "Service", "cnt", 10L),
                        Map.of("nodeType", "Controller", "cnt", 5L),
                        Map.of("nodeType", "ApiEndpoint", "cnt", 15L),
                        Map.of("nodeType", "Method", "cnt", 40L)
                ));
        when(graphDao.countIsolatedNodes(eq("proj-4"), eq("v4"))).thenReturn(5L);
        when(graphDao.averageNodeDegree(eq("proj-4"), eq("v4"))).thenReturn(3.5);
        when(nodeEvidenceRepository.countDistinctNodeIds(eq("proj-4"), eq("v4"))).thenReturn(80L);
        when(edgeEvidenceRepository.countDistinctEdgeIds(eq("proj-4"), eq("v4"))).thenReturn(60L);

        // when
        assessor.assessAndReport("proj-4", "v4");

        // then: 节点覆盖率 80/100 = 80.0%，边覆盖率 60/200 = 30.0%
        String content = Files.readString(reportFile("proj-4"));
        assertThat(content).contains("## 7. 证据覆盖率");
        assertThat(content).contains("80/100 (80.0%)");
        assertThat(content).contains("60/200 (30.0%)");
    }

    // ========================================================
    // 场景 5：置信度校准 — 按来源分桶统计 Claim 状态
    // ========================================================

    @Test
    @DisplayName("置信度校准应按来源类型分桶展示确认/待确认/驳回分布")
    void shouldCalibrateBySourceTypeBuckets() throws Exception {
        // given
        stubDefaults("proj-5", "v5", 100, 200);
        when(graphDao.nodeTypeDistribution(eq("proj-5"), eq("v5")))
                .thenReturn(List.of(
                        Map.of("nodeType", "Table", "cnt", 20L),
                        Map.of("nodeType", "Column", "cnt", 50L),
                        Map.of("nodeType", "Service", "cnt", 10L),
                        Map.of("nodeType", "Controller", "cnt", 5L),
                        Map.of("nodeType", "ApiEndpoint", "cnt", 15L),
                        Map.of("nodeType", "Method", "cnt", 40L)
                ));
        when(graphDao.countIsolatedNodes(eq("proj-5"), eq("v5"))).thenReturn(5L);
        when(graphDao.averageNodeDegree(eq("proj-5"), eq("v5"))).thenReturn(3.5);
        when(nodeEvidenceRepository.countDistinctNodeIds(eq("proj-5"), eq("v5"))).thenReturn(80L);
        when(edgeEvidenceRepository.countDistinctEdgeIds(eq("proj-5"), eq("v5"))).thenReturn(100L);

        // 按来源分桶：CODE 已确认 10、待确认 5、驳回 2
        when(knowledgeClaimService.countByStatus(eq("proj-5"), eq("v5"), eq("CODE"), eq("CONFIRMED")))
                .thenReturn(10L);
        when(knowledgeClaimService.countByStatus(eq("proj-5"), eq("v5"), eq("CODE"), eq("PENDING_CONFIRM")))
                .thenReturn(5L);
        when(knowledgeClaimService.countByStatus(eq("proj-5"), eq("v5"), eq("CODE"), eq("REJECTED")))
                .thenReturn(2L);
        // DOC_AI：已确认 0、待确认 20、驳回 3
        when(knowledgeClaimService.countByStatus(eq("proj-5"), eq("v5"), eq("DOC_AI"), eq("CONFIRMED")))
                .thenReturn(0L);
        when(knowledgeClaimService.countByStatus(eq("proj-5"), eq("v5"), eq("DOC_AI"), eq("PENDING_CONFIRM")))
                .thenReturn(20L);
        when(knowledgeClaimService.countByStatus(eq("proj-5"), eq("v5"), eq("DOC_AI"), eq("REJECTED")))
                .thenReturn(3L);
        // AI_INFERENCE：已确认 1、待确认 8、驳回 0
        when(knowledgeClaimService.countByStatus(eq("proj-5"), eq("v5"), eq("AI_INFERENCE"), eq("CONFIRMED")))
                .thenReturn(1L);
        when(knowledgeClaimService.countByStatus(eq("proj-5"), eq("v5"), eq("AI_INFERENCE"), eq("PENDING_CONFIRM")))
                .thenReturn(8L);
        when(knowledgeClaimService.countByStatus(eq("proj-5"), eq("v5"), eq("AI_INFERENCE"), eq("REJECTED")))
                .thenReturn(0L);
        // RUNTIME：已确认 15、待确认 0、驳回 0
        when(knowledgeClaimService.countByStatus(eq("proj-5"), eq("v5"), eq("RUNTIME"), eq("CONFIRMED")))
                .thenReturn(15L);
        when(knowledgeClaimService.countByStatus(eq("proj-5"), eq("v5"), eq("RUNTIME"), eq("PENDING_CONFIRM")))
                .thenReturn(0L);
        when(knowledgeClaimService.countByStatus(eq("proj-5"), eq("v5"), eq("RUNTIME"), eq("REJECTED")))
                .thenReturn(0L);

        // when
        assessor.assessAndReport("proj-5", "v5");

        // then: 校准表格应包含各来源的分桶数据
        String content = Files.readString(reportFile("proj-5"));
        assertThat(content).contains("## 8. 来源状态分布");
        assertThat(content).contains("| 来源类型 | 已确认 | 待确认 | 已驳回 |");
        // CODE 行
        assertThat(content).contains("| CODE | 10 | 5 | 2 |");
        // DOC_AI 行
        assertThat(content).contains("| DOC_AI | 0 | 20 | 3 |");
        // AI_INFERENCE 行
        assertThat(content).contains("| AI_INFERENCE | 1 | 8 | 0 |");
        // RUNTIME 行
        assertThat(content).contains("| RUNTIME | 15 | 0 | 0 |");
    }

    // ========================================================
    // 场景 6：三元组准确率 — 抽样边有证据支撑
    // ========================================================

    @Test
    @DisplayName("三元组准确率应统计有证据支撑的抽样边比例")
    void shouldReportTripleAccuracyWithEvidence() throws Exception {
        // given: 100 节点 200 边
        stubDefaults("proj-6", "v6", 100, 200);
        when(graphDao.nodeTypeDistribution(eq("proj-6"), eq("v6")))
                .thenReturn(List.of(
                        Map.of("nodeType", "Table", "cnt", 20L),
                        Map.of("nodeType", "Column", "cnt", 50L),
                        Map.of("nodeType", "Service", "cnt", 10L),
                        Map.of("nodeType", "Controller", "cnt", 5L),
                        Map.of("nodeType", "ApiEndpoint", "cnt", 15L),
                        Map.of("nodeType", "Method", "cnt", 40L)
                ));
        when(graphDao.countIsolatedNodes(eq("proj-6"), eq("v6"))).thenReturn(5L);
        when(graphDao.averageNodeDegree(eq("proj-6"), eq("v6"))).thenReturn(3.5);
        when(nodeEvidenceRepository.countDistinctNodeIds(eq("proj-6"), eq("v6"))).thenReturn(80L);
        when(edgeEvidenceRepository.countDistinctEdgeIds(eq("proj-6"), eq("v6"))).thenReturn(100L);

        // 抽样 10 条边，其中 8 条有 EdgeEvidence 关联
        when(graphDao.sampleEdgesWithEvidence(eq("proj-6"), eq("v6"), anyInt()))
                .thenReturn(List.of(
                        Map.of("edgeId", "e1", "fromNodeId", "n1", "toNodeId", "n2", "edgeType", "CONTAINS"),
                        Map.of("edgeId", "e2", "fromNodeId", "n3", "toNodeId", "n4", "edgeType", "CALLS"),
                        Map.of("edgeId", "e3", "fromNodeId", "n5", "toNodeId", "n6", "edgeType", "READS"),
                        Map.of("edgeId", "e4", "fromNodeId", "n7", "toNodeId", "n8", "edgeType", "WRITES"),
                        Map.of("edgeId", "e5", "fromNodeId", "n9", "toNodeId", "n10", "edgeType", "BELONGS_TO"),
                        Map.of("edgeId", "e6", "fromNodeId", "n11", "toNodeId", "n12", "edgeType", "HANDLED_BY"),
                        Map.of("edgeId", "e7", "fromNodeId", "n13", "toNodeId", "n14", "edgeType", "HAS_COLUMN"),
                        Map.of("edgeId", "e8", "fromNodeId", "n15", "toNodeId", "n16", "edgeType", "CONTAINS"),
                        Map.of("edgeId", "e9", "fromNodeId", "n17", "toNodeId", "n18", "edgeType", "CALLS"),
                        Map.of("edgeId", "e10", "fromNodeId", "n19", "toNodeId", "n20", "edgeType", "READS")
                ));
        // e1~e8 有证据，e9/e10 无证据
        EdgeEvidence ev1 = edgeEvidence("e1");
        EdgeEvidence ev2 = edgeEvidence("e2");
        EdgeEvidence ev3 = edgeEvidence("e3");
        EdgeEvidence ev4 = edgeEvidence("e4");
        EdgeEvidence ev5 = edgeEvidence("e5");
        EdgeEvidence ev6 = edgeEvidence("e6");
        EdgeEvidence ev7 = edgeEvidence("e7");
        EdgeEvidence ev8 = edgeEvidence("e8");
        when(edgeEvidenceRepository.selectList(any()))
                .thenReturn(List.of(ev1, ev2, ev3, ev4, ev5, ev6, ev7, ev8));

        // when
        assessor.assessAndReport("proj-6", "v6");

        // then: 抽样 10，有证据支撑 8，准确率 80.0%
        String content = Files.readString(reportFile("proj-6"));
        assertThat(content).contains("## 6. 抽样边证据支撑率");
        assertThat(content).contains("抽样数: 10");
        assertThat(content).contains("有证据支撑边数: 8");
        assertThat(content).contains("证据支撑率: 80.0%");
        // 证据支撑率 < 90% 应有建议
        assertThat(content).contains("抽样边证据支撑率仅为 80.0%");
    }

    // ========================================================
    // 场景 7：约束违反 — 存在缺失必需边的节点
    // ========================================================

    @Test
    @DisplayName("约束违反应在报告中体现违反数和改进建议")
    void shouldReportConstraintViolations() throws Exception {
        // given: 50 节点 30 边（边/节点比 < 1.0）
        stubDefaults("proj-7", "v7", 50, 30);
        when(graphDao.nodeTypeDistribution(eq("proj-7"), eq("v7")))
                .thenReturn(List.of(
                        Map.of("nodeType", "Table", "cnt", 10L),
                        Map.of("nodeType", "Method", "cnt", 20L),
                        Map.of("nodeType", "ApiEndpoint", "cnt", 5L)
                ));
        when(graphDao.edgeTypeDistribution(eq("proj-7"), eq("v7")))
                .thenReturn(List.of(Map.of("edgeType", "CALLS", "cnt", 30L)));
        when(graphDao.countIsolatedNodes(eq("proj-7"), eq("v7"))).thenReturn(10L);
        when(graphDao.averageNodeDegree(eq("proj-7"), eq("v7"))).thenReturn(0.8);
        // 约束违反：Method 5 个，ApiEndpoint 2 个
        when(graphDao.countNodesWithoutEdgeTypes(eq("proj-7"), eq("v7"), eq("Method"), any()))
                .thenReturn(5L);
        when(graphDao.countNodesWithoutEdgeTypes(eq("proj-7"), eq("v7"), eq("ApiEndpoint"), any()))
                .thenReturn(2L);
        when(nodeEvidenceRepository.countDistinctNodeIds(eq("proj-7"), eq("v7"))).thenReturn(40L);
        when(edgeEvidenceRepository.countDistinctEdgeIds(eq("proj-7"), eq("v7"))).thenReturn(20L);

        // when
        assessor.assessAndReport("proj-7", "v7");

        // then
        String content = Files.readString(reportFile("proj-7"));
        assertThat(content).contains("边/节点比: 0.60");
        assertThat(content).contains("⚠️ 5 个违反");
        assertThat(content).contains("⚠️ 2 个违反");
        // 结构完整性章节应显示约束违反总数 7
        assertThat(content).contains("## 5. 结构完整性");
        assertThat(content).contains("约束违反总数: 7");
        // 改进建议
        assertThat(content).contains("## 9. 改进建议");
        assertThat(content).contains("边/节点比仅为 0.60");
        assertThat(content).contains("关键节点类型 Column 数量为 0");
    }

    // ========================================================
    // 辅助：构造仅含 edgeId 的 EdgeEvidence
    // ========================================================

    private static EdgeEvidence edgeEvidence(String edgeId) {
        EdgeEvidence ev = new EdgeEvidence();
        ev.setEdgeId(edgeId);
        return ev;
    }
}
