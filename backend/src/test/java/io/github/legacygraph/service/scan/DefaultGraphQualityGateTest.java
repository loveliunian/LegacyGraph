package io.github.legacygraph.service.scan;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.scan.Decision;
import io.github.legacygraph.dto.scan.GraphQualitySnapshot;
import io.github.legacygraph.repository.NodeEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * DefaultGraphQualityGate 单元测试 — 验证门禁规则逻辑。
 *
 * <p>测试场景：
 * <ul>
 *   <li>全部门禁通过：边/节点比≥1.0、孤立率≤10%、无约束违反、证据覆盖率≥95% → passed=true</li>
 *   <li>边/节点比低于 1.0 → passed=false，reasons 含 EDGE_NODE_RATIO_BELOW_1</li>
 *   <li>孤立节点率超过 10% → passed=false，reasons 含 ISOLATED_RATE_ABOVE_10_PERCENT</li>
 *   <li>存在约束违反 → passed=false，reasons 含 CONSTRAINT_VIOLATIONS</li>
 *   <li>证据覆盖率低于 95% → passed=false，reasons 含 EVIDENCE_RATE_BELOW_95_PERCENT</li>
 *   <li>多规则同时违反 → reasons 包含全部违反规则名</li>
 *   <li>空图谱 → passed=false（边/节点比=0&lt;1、覆盖率=0&lt;95%）</li>
 *   <li>边界值：边/节点比=1.0 通过、孤立率=10% 通过、证据覆盖率=95% 通过</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultGraphQualityGate 质量门禁规则测试")
class DefaultGraphQualityGateTest {

    @Mock
    private Neo4jGraphDao graphDao;

    @Mock
    private NodeEvidenceRepository nodeEvidenceRepository;

    private DefaultGraphQualityGate gate;

    private static final String PID = "proj-test";
    private static final String VID = "v-test";

    @BeforeEach
    void setUp() {
        gate = new DefaultGraphQualityGate(graphDao, nodeEvidenceRepository);
    }

    /**
     * 设置 lenient 默认 stub，返回全零/空数据。
     * 各测试可在此基础上覆盖特定 stub。
     *
     * @param nodes 节点总数
     * @param edges 边总数
     */
    private void stubDefaults(long nodes, long edges) {
        lenient().when(graphDao.versionGraphStats(eq(PID), eq(VID)))
                .thenReturn(Map.of("totalNodes", nodes, "totalEdges", edges));
        lenient().when(graphDao.countIsolatedNodes(eq(PID), eq(VID))).thenReturn(0L);
        lenient().when(graphDao.countNodesWithoutEdgeTypes(eq(PID), eq(VID), any(), any())).thenReturn(0L);
        lenient().when(nodeEvidenceRepository.countDistinctNodeIds(eq(PID), eq(VID))).thenReturn(0L);
    }

    // ========================================================
    // 场景 1：全部门禁通过
    // ========================================================

    @Test
    @DisplayName("全部指标健康时应通过门禁")
    void shouldPassWhenAllMetricsHealthy() {
        // given: 100 节点 200 边（比率 2.0），孤立 5（5%），无约束违反，证据 96（96%）
        stubDefaults(100, 200);
        when(graphDao.countIsolatedNodes(eq(PID), eq(VID))).thenReturn(5L);
        when(nodeEvidenceRepository.countDistinctNodeIds(eq(PID), eq(VID))).thenReturn(96L);

        // when
        Decision decision = gate.evaluate(PID, VID);

        // then
        assertThat(decision.passed()).isTrue();
        assertThat(decision.reasons()).isEmpty();
    }

    // ========================================================
    // 场景 2：边/节点比低于 1.0
    // ========================================================

    @Test
    @DisplayName("边/节点比低于 1.0 应触发 EDGE_NODE_RATIO_BELOW_1")
    void shouldFailWhenEdgeNodeRatioBelowOne() {
        // given: 100 节点 80 边（比率 0.8），孤立 5（5%），无约束违反，证据 100（100%）
        stubDefaults(100, 80);
        when(graphDao.countIsolatedNodes(eq(PID), eq(VID))).thenReturn(5L);
        when(nodeEvidenceRepository.countDistinctNodeIds(eq(PID), eq(VID))).thenReturn(100L);

        // when
        Decision decision = gate.evaluate(PID, VID);

        // then
        assertThat(decision.passed()).isFalse();
        assertThat(decision.reasons())
                .containsExactly(GraphQualityGate.RULE_EDGE_NODE_RATIO_BELOW_1);
    }

    // ========================================================
    // 场景 3：孤立节点率超过 10%
    // ========================================================

    @Test
    @DisplayName("孤立节点率超过 10% 应触发 ISOLATED_RATE_ABOVE_10_PERCENT")
    void shouldFailWhenIsolatedRateAboveTenPercent() {
        // given: 100 节点 200 边（比率 2.0），孤立 11（11%），无约束违反，证据 100（100%）
        stubDefaults(100, 200);
        when(graphDao.countIsolatedNodes(eq(PID), eq(VID))).thenReturn(11L);
        when(nodeEvidenceRepository.countDistinctNodeIds(eq(PID), eq(VID))).thenReturn(100L);

        // when
        Decision decision = gate.evaluate(PID, VID);

        // then
        assertThat(decision.passed()).isFalse();
        assertThat(decision.reasons())
                .containsExactly(GraphQualityGate.RULE_ISOLATED_RATE_ABOVE_10_PERCENT);
    }

    // ========================================================
    // 场景 4：存在约束违反
    // ========================================================

    @Test
    @DisplayName("存在约束违反应触发 CONSTRAINT_VIOLATIONS")
    void shouldFailWhenConstraintViolationsExist() {
        // given: 100 节点 200 边（比率 2.0），孤立 5（5%），Method 约束违反 3 个，证据 100（100%）
        stubDefaults(100, 200);
        when(graphDao.countIsolatedNodes(eq(PID), eq(VID))).thenReturn(5L);
        when(graphDao.countNodesWithoutEdgeTypes(eq(PID), eq(VID), eq("Method"), any()))
                .thenReturn(3L);
        when(nodeEvidenceRepository.countDistinctNodeIds(eq(PID), eq(VID))).thenReturn(100L);

        // when
        Decision decision = gate.evaluate(PID, VID);

        // then
        assertThat(decision.passed()).isFalse();
        assertThat(decision.reasons())
                .containsExactly(GraphQualityGate.RULE_CONSTRAINT_VIOLATIONS);
    }

    // ========================================================
    // 场景 5：证据覆盖率低于 95%
    // ========================================================

    @Test
    @DisplayName("证据覆盖率低于 95% 应触发 EVIDENCE_RATE_BELOW_95_PERCENT")
    void shouldFailWhenEvidenceCoverageBelow95Percent() {
        // given: 100 节点 200 边（比率 2.0），孤立 5（5%），无约束违反，证据 94（94%）
        stubDefaults(100, 200);
        when(graphDao.countIsolatedNodes(eq(PID), eq(VID))).thenReturn(5L);
        when(nodeEvidenceRepository.countDistinctNodeIds(eq(PID), eq(VID))).thenReturn(94L);

        // when
        Decision decision = gate.evaluate(PID, VID);

        // then
        assertThat(decision.passed()).isFalse();
        assertThat(decision.reasons())
                .containsExactly(GraphQualityGate.RULE_EVIDENCE_RATE_BELOW_95_PERCENT);
    }

    // ========================================================
    // 场景 6：多规则同时违反
    // ========================================================

    @Test
    @DisplayName("多规则同时违反时 reasons 应包含全部违反规则名")
    void shouldReportAllViolationsWhenMultipleRulesBroken() {
        // given: 100 节点 50 边（比率 0.5<1），孤立 15（15%>10%），
        //        Method 约束违反 2，证据 50（50%<95%）
        stubDefaults(100, 50);
        when(graphDao.countIsolatedNodes(eq(PID), eq(VID))).thenReturn(15L);
        when(graphDao.countNodesWithoutEdgeTypes(eq(PID), eq(VID), eq("Method"), any()))
                .thenReturn(2L);
        when(nodeEvidenceRepository.countDistinctNodeIds(eq(PID), eq(VID))).thenReturn(50L);

        // when
        Decision decision = gate.evaluate(PID, VID);

        // then: 四条规则全部违反
        assertThat(decision.passed()).isFalse();
        assertThat(decision.reasons()).hasSize(4);
        assertThat(decision.reasons()).containsExactlyInAnyOrder(
                GraphQualityGate.RULE_EDGE_NODE_RATIO_BELOW_1,
                GraphQualityGate.RULE_ISOLATED_RATE_ABOVE_10_PERCENT,
                GraphQualityGate.RULE_CONSTRAINT_VIOLATIONS,
                GraphQualityGate.RULE_EVIDENCE_RATE_BELOW_95_PERCENT);
    }

    // ========================================================
    // 场景 7：空图谱
    // ========================================================

    @Test
    @DisplayName("空图谱应因边/节点比和证据覆盖率未达标而拦截")
    void shouldFailForEmptyGraph() {
        // given: 0 节点 0 边
        stubDefaults(0, 0);

        // when
        Decision decision = gate.evaluate(PID, VID);

        // then: 边/节点比=0.0<1.0，证据覆盖率=0.0<0.95，孤立率=0.0（不触发），约束违反=0（不触发）
        assertThat(decision.passed()).isFalse();
        assertThat(decision.reasons()).containsExactlyInAnyOrder(
                GraphQualityGate.RULE_EDGE_NODE_RATIO_BELOW_1,
                GraphQualityGate.RULE_EVIDENCE_RATE_BELOW_95_PERCENT);
    }

    // ========================================================
    // 场景 8：边界值 — 恰好等于阈值应通过
    // ========================================================

    @Test
    @DisplayName("边界值（比率=1.0、孤立率=10%、覆盖率=95%）应通过门禁")
    void shouldPassAtBoundaryValues() {
        // given: 100 节点 100 边（比率正好 1.0），孤立 10（10%），无约束违反，证据 95（95%）
        stubDefaults(100, 100);
        when(graphDao.countIsolatedNodes(eq(PID), eq(VID))).thenReturn(10L);
        when(nodeEvidenceRepository.countDistinctNodeIds(eq(PID), eq(VID))).thenReturn(95L);

        // when
        Decision decision = gate.evaluate(PID, VID);

        // then: 边界值用 < 和 > 判断，等于阈值应通过
        assertThat(decision.passed()).isTrue();
        assertThat(decision.reasons()).isEmpty();
    }

    // ========================================================
    // 场景 9：快照数据采集验证
    // ========================================================

    @Test
    @DisplayName("快照应正确采集各项指标")
    void shouldCollectCorrectSnapshot() {
        // given: 200 节点 500 边，孤立 20（10%），Method 违反 5、ApiEndpoint 违反 3（共 8），证据 180（90%）
        stubDefaults(200, 500);
        when(graphDao.countIsolatedNodes(eq(PID), eq(VID))).thenReturn(20L);
        when(graphDao.countNodesWithoutEdgeTypes(eq(PID), eq(VID), eq("Method"), any())).thenReturn(5L);
        when(graphDao.countNodesWithoutEdgeTypes(eq(PID), eq(VID), eq("ApiEndpoint"), any())).thenReturn(3L);
        when(nodeEvidenceRepository.countDistinctNodeIds(eq(PID), eq(VID))).thenReturn(180L);

        // when
        GraphQualitySnapshot snapshot = gate.collectSnapshot(PID, VID);

        // then
        assertThat(snapshot.totalNodes()).isEqualTo(200);
        assertThat(snapshot.totalEdges()).isEqualTo(500);
        assertThat(snapshot.edgeNodeRatio()).isEqualTo(2.5);
        assertThat(snapshot.isolatedNodeCount()).isEqualTo(20);
        assertThat(snapshot.isolatedRate()).isEqualTo(0.10);
        assertThat(snapshot.constraintViolations()).isEqualTo(8);
        assertThat(snapshot.nodesWithEvidence()).isEqualTo(180);
        assertThat(snapshot.evidenceCoverageRate()).isEqualTo(0.90);
    }

    // ========================================================
    // 场景 10：多条约束规则汇总
    // ========================================================

    @Test
    @DisplayName("约束违反数应汇总所有约束规则的违反计数")
    void shouldSumAllConstraintViolations() {
        // given: 100 节点 200 边，孤立 5（5%），四条约束规则各违反 1 个，证据 100（100%）
        stubDefaults(100, 200);
        when(graphDao.countIsolatedNodes(eq(PID), eq(VID))).thenReturn(5L);
        when(graphDao.countNodesWithoutEdgeTypes(eq(PID), eq(VID), eq("Method"), any())).thenReturn(1L);
        when(graphDao.countNodesWithoutEdgeTypes(eq(PID), eq(VID), eq("Column"), any())).thenReturn(1L);
        when(graphDao.countNodesWithoutEdgeTypes(eq(PID), eq(VID), eq("ApiEndpoint"), any())).thenReturn(1L);
        when(graphDao.countNodesWithoutEdgeTypes(eq(PID), eq(VID), eq("SqlStatement"), any())).thenReturn(1L);
        when(nodeEvidenceRepository.countDistinctNodeIds(eq(PID), eq(VID))).thenReturn(100L);

        // when
        Decision decision = gate.evaluate(PID, VID);

        // then: 约束违反总数 = 4，触发 CONSTRAINT_VIOLATIONS
        assertThat(decision.passed()).isFalse();
        assertThat(decision.reasons())
                .containsExactly(GraphQualityGate.RULE_CONSTRAINT_VIOLATIONS);
    }
}
