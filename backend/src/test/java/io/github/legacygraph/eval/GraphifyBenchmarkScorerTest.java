package io.github.legacygraph.eval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphifyBenchmarkScorer 单元测试。
 */
class GraphifyBenchmarkScorerTest {

    private GraphifyBenchmarkScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new GraphifyBenchmarkScorer();
    }

    @Test
    @DisplayName("完美匹配: 所有期望节点和边都存在，无禁止边")
    void perfectMatch() {
        GraphifyBenchmarkCase testCase = new GraphifyBenchmarkCase(
            "spring-vue-sql",
            Set.of("class:UserService", "class:UserController"),
            Set.of("CALLS:UserController->UserService"),
            Set.of("WRITES:UserController->users")
        );

        GraphifyBenchmarkResult result = scorer.score(
            testCase,
            Set.of("class:UserService", "class:UserController"),
            Set.of("CALLS:UserController->UserService")
        );

        assertEquals("spring-vue-sql", result.caseName());
        assertEquals(1.0, result.nodeRecall());
        assertEquals(1.0, result.edgeRecall());
        assertEquals(0.0, result.forbiddenEdgeRate());
        assertTrue(result.passesReleaseGate());
    }

    @Test
    @DisplayName("节点召回率不足: 缺少期望节点")
    void nodeRecallBelowThreshold() {
        GraphifyBenchmarkCase testCase = new GraphifyBenchmarkCase(
            "test-case",
            Set.of("node:1", "node:2", "node:3", "node:4", "node:5",
                   "node:6", "node:7", "node:8", "node:9", "node:10"),
            Set.of(),
            Set.of()
        );

        // 只导入了 8 个节点，召回率 = 0.8 < 0.9
        GraphifyBenchmarkResult result = scorer.score(
            testCase,
            Set.of("node:1", "node:2", "node:3", "node:4", "node:5",
                   "node:6", "node:7", "node:8"),
            Set.of()
        );

        assertEquals(0.8, result.nodeRecall());
        assertFalse(result.passesReleaseGate());
        assertTrue(result.getReleaseGateDetails().contains("节点召回率"));
    }

    @Test
    @DisplayName("边召回率不足: 缺少期望边")
    void edgeRecallBelowThreshold() {
        GraphifyBenchmarkCase testCase = new GraphifyBenchmarkCase(
            "test-case",
            Set.of(),
            Set.of("edge:1", "edge:2", "edge:3", "edge:4", "edge:5",
                   "edge:6", "edge:7", "edge:8", "edge:9", "edge:10"),
            Set.of()
        );

        // 只导入了 8 条边，召回率 = 0.8 < 0.85
        GraphifyBenchmarkResult result = scorer.score(
            testCase,
            Set.of(),
            Set.of("edge:1", "edge:2", "edge:3", "edge:4", "edge:5",
                   "edge:6", "edge:7", "edge:8")
        );

        assertEquals(0.8, result.edgeRecall());
        assertFalse(result.passesReleaseGate());
        assertTrue(result.getReleaseGateDetails().contains("边召回率"));
    }

    @Test
    @DisplayName("禁止边出现导致发布门槛失败")
    void releaseGateFailsWhenForbiddenEdgeAppears() {
        GraphifyBenchmarkCase testCase = new GraphifyBenchmarkCase(
            "dynamic-sql",
            Set.of("mapper:OrderMapper"),
            Set.of("READS:OrderMapper->orders"),
            Set.of("WRITES:OrderMapper->audit_log")
        );

        GraphifyBenchmarkResult result = scorer.score(
            testCase,
            Set.of("mapper:OrderMapper"),
            Set.of("READS:OrderMapper->orders", "WRITES:OrderMapper->audit_log")
        );

        assertFalse(result.passesReleaseGate());
        assertEquals(1, result.forbiddenEdgesFound().size());
        assertTrue(result.forbiddenEdgesFound().contains("WRITES:OrderMapper->audit_log"));
        assertTrue(result.getReleaseGateDetails().contains("禁止边出现率"));
    }

    @Test
    @DisplayName("多项指标不达标: 同时违反多个门槛")
    void multipleThresholdsViolated() {
        GraphifyBenchmarkCase testCase = new GraphifyBenchmarkCase(
            "test-case",
            Set.of("node:1", "node:2"),
            Set.of("edge:1"),
            Set.of("forbidden:1")
        );

        GraphifyBenchmarkResult result = scorer.score(
            testCase,
            Set.of("node:1"), // 缺少 node:2
            Set.of("edge:1", "forbidden:1") // 出现禁止边
        );

        assertEquals(0.5, result.nodeRecall());
        assertEquals(1.0, result.edgeRecall());
        assertEquals(0.5, result.forbiddenEdgeRate());
        assertFalse(result.passesReleaseGate());
        assertTrue(result.getReleaseGateDetails().contains("节点召回率"));
        assertTrue(result.getReleaseGateDetails().contains("禁止边出现率"));
    }

    @Test
    @DisplayName("空期望集合: 召回率为 1.0")
    void emptyExpectedSets() {
        GraphifyBenchmarkCase testCase = new GraphifyBenchmarkCase(
            "empty-case",
            Set.of(),
            Set.of(),
            Set.of()
        );

        GraphifyBenchmarkResult result = scorer.score(
            testCase,
            Set.of("node:1"),
            Set.of("edge:1")
        );

        assertEquals(1.0, result.nodeRecall());
        assertEquals(1.0, result.edgeRecall());
        assertEquals(0.0, result.forbiddenEdgeRate());
        assertTrue(result.passesReleaseGate());
    }

    @Test
    @DisplayName("边界情况: 刚好达到门槛")
    void exactThresholds() {
        GraphifyBenchmarkCase testCase = new GraphifyBenchmarkCase(
            "threshold-case",
            Set.of("node:1", "node:2", "node:3", "node:4", "node:5",
                   "node:6", "node:7", "node:8", "node:9", "node:10"),
            Set.of("edge:1", "edge:2", "edge:3", "edge:4", "edge:5",
                   "edge:6", "edge:7", "edge:8", "edge:9", "edge:10"),
            Set.of("forbidden:1")
        );

        // nodeRecall = 0.9 (9/10)
        // edgeRecall = 0.9 (9/10)
        // forbiddenEdgeRate = 0 (0/10)
        GraphifyBenchmarkResult result = scorer.score(
            testCase,
            Set.of("node:1", "node:2", "node:3", "node:4", "node:5",
                   "node:6", "node:7", "node:8", "node:9"),
            Set.of("edge:1", "edge:2", "edge:3", "edge:4", "edge:5",
                   "edge:6", "edge:7", "edge:8", "edge:9")
        );

        assertTrue(result.passesReleaseGate());
    }

    @Test
    @DisplayName("null testCase 抛出异常")
    void nullTestCaseThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            scorer.score(null, Set.of(), Set.of()));
    }

    @Test
    @DisplayName("null actualKeys 视为空集合")
    void nullActualKeysTreatedAsEmpty() {
        GraphifyBenchmarkCase testCase = new GraphifyBenchmarkCase(
            "test-case",
            Set.of("node:1"),
            Set.of("edge:1"),
            Set.of()
        );

        GraphifyBenchmarkResult result = scorer.score(testCase, null, null);

        assertEquals(0.0, result.nodeRecall());
        assertEquals(0.0, result.edgeRecall());
        assertEquals(0.0, result.forbiddenEdgeRate());
        assertFalse(result.passesReleaseGate());
    }
}
