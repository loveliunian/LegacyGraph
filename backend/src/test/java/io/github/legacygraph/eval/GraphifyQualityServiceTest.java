package io.github.legacygraph.eval;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.ScanVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * GraphifyQualityService 单元测试。
 * <p>
 * 覆盖：无数据、有数据无 benchmark 用例、benchmark 通过/失败 四条路径。
 * versionId 显式传入以跳过 ScanVersionRepository 查询。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class GraphifyQualityServiceTest {

    @Mock
    private Neo4jGraphDao graphDao;

    @Mock
    private ScanVersionRepository scanVersionRepository;

    private GraphifyBenchmarkScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new GraphifyBenchmarkScorer();
    }

    private GraphifyQualityService serviceWithCases(List<GraphifyBenchmarkCase> cases) {
        GraphifyBenchmarkCaseRegistry registry = new GraphifyBenchmarkCaseRegistry() {
            @Override
            public List<GraphifyBenchmarkCase> cases() {
                return cases;
            }
        };
        return new GraphifyQualityService(graphDao, scorer, registry, scanVersionRepository);
    }

    @Test
    void noDataAndNoCases_returnsFailedGateWithReason() {
        GraphifyQualityService service = serviceWithCases(List.of());
        when(graphDao.queryNodeKeysBySourceTypes(eq("p1"), eq("v1"), anyList())).thenReturn(Set.of());
        when(graphDao.queryEdgeKeysBySourceTypes(eq("p1"), eq("v1"), anyList())).thenReturn(Set.of());

        GraphifyQualityResult r = service.getQuality("p1", "v1");

        assertThat(r.releaseGatePassed()).isFalse();
        assertThat(r.releaseGateReason()).isEqualTo("无 Graphify 导入数据");
        assertThat(r.overallScore()).isEqualTo(0.0);
        assertThat(r.nodeCoverage()).isEqualTo(0.0);
        assertThat(r.edgeCoverage()).isEqualTo(0.0);
        assertThat(r.benchmarkResults()).isEmpty();
    }

    @Test
    void dataPresentNoCases_returnsPassedGateWithFullCoverage() {
        GraphifyQualityService service = serviceWithCases(List.of());
        when(graphDao.queryNodeKeysBySourceTypes(eq("p1"), eq("v1"), anyList()))
                .thenReturn(Set.of("class:A", "class:B"));
        when(graphDao.queryEdgeKeysBySourceTypes(eq("p1"), eq("v1"), anyList()))
                .thenReturn(Set.of("CALLS:A->B"));

        GraphifyQualityResult r = service.getQuality("p1", "v1");

        assertThat(r.releaseGatePassed()).isTrue();
        assertThat(r.releaseGateReason()).isNull();
        assertThat(r.nodeCoverage()).isEqualTo(1.0);
        assertThat(r.edgeCoverage()).isEqualTo(1.0);
        assertThat(r.overallScore()).isEqualTo(100.0);
        assertThat(r.benchmarkResults()).isEmpty();
    }

    @Test
    void passingBenchmarkCase_marksGatePassed() {
        GraphifyBenchmarkCase tc = new GraphifyBenchmarkCase("spring-vue-sql",
                Set.of("class:A", "class:B"), Set.of("CALLS:A->B"), Set.of());
        GraphifyQualityService service = serviceWithCases(List.of(tc));
        when(graphDao.queryNodeKeysBySourceTypes(eq("p1"), eq("v1"), anyList()))
                .thenReturn(Set.of("class:A", "class:B", "class:C"));
        when(graphDao.queryEdgeKeysBySourceTypes(eq("p1"), eq("v1"), anyList()))
                .thenReturn(Set.of("CALLS:A->B", "CALLS:B->C"));

        GraphifyQualityResult r = service.getQuality("p1", "v1");

        assertThat(r.releaseGatePassed()).isTrue();
        assertThat(r.releaseGateReason()).isNull();
        assertThat(r.benchmarkResults()).hasSize(1);
        GraphifyQualityResult.BenchmarkItem item = r.benchmarkResults().get(0);
        assertThat(item.name()).isEqualTo("spring-vue-sql");
        assertThat(item.passed()).isTrue();
        assertThat(item.score()).isCloseTo(1.0, within(0.0001));
        assertThat(item.threshold()).isEqualTo(0.9);
        assertThat(r.nodeCoverage()).isCloseTo(1.0, within(0.0001));
        assertThat(r.edgeCoverage()).isCloseTo(1.0, within(0.0001));
    }

    @Test
    void failingBenchmarkCase_marksGateFailedWithReason() {
        // 期望 10 个节点，实际只命中 8 个 -> nodeRecall 0.8 < 0.9 -> 发布门槛失败
        Set<String> expected = Set.of("n1", "n2", "n3", "n4", "n5", "n6", "n7", "n8", "n9", "n10");
        Set<String> actual = Set.of("n1", "n2", "n3", "n4", "n5", "n6", "n7", "n8");
        GraphifyBenchmarkCase tc = new GraphifyBenchmarkCase("coverage-case", expected, Set.of(), Set.of());
        GraphifyQualityService service = serviceWithCases(List.of(tc));
        when(graphDao.queryNodeKeysBySourceTypes(eq("p1"), eq("v1"), anyList())).thenReturn(actual);
        when(graphDao.queryEdgeKeysBySourceTypes(eq("p1"), eq("v1"), anyList())).thenReturn(Set.of());

        GraphifyQualityResult r = service.getQuality("p1", "v1");

        assertThat(r.releaseGatePassed()).isFalse();
        assertThat(r.releaseGateReason()).contains("节点召回率");
        assertThat(r.benchmarkResults()).hasSize(1);
        assertThat(r.benchmarkResults().get(0).passed()).isFalse();
        assertThat(r.benchmarkResults().get(0).score()).isCloseTo(0.8, within(0.0001));
    }
}
