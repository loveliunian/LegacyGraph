package io.github.legacygraph.eval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * Graphify benchmark 评分服务。
 * <p>
 * 用固定样本衡量 Graphify 融合质量，计算节点召回率、边召回率和禁止边出现率。
 * </p>
 */
@Slf4j
@Service
public class GraphifyBenchmarkScorer {

    /**
     * 对 benchmark case 进行评分。
     *
     * @param testCase          benchmark 测试用例
     * @param actualNodeKeys    实际导入的节点键集合
     * @param actualEdgeKeys    实际导入的边键集合
     * @return 评测结果
     */
    public GraphifyBenchmarkResult score(
            GraphifyBenchmarkCase testCase,
            Set<String> actualNodeKeys,
            Set<String> actualEdgeKeys) {

        if (testCase == null) {
            throw new IllegalArgumentException("testCase 不能为空");
        }
        if (actualNodeKeys == null) actualNodeKeys = Set.of();
        if (actualEdgeKeys == null) actualEdgeKeys = Set.of();

        // 计算节点召回率
        Set<String> missingExpectedNodes = new HashSet<>(testCase.expectedNodeKeys());
        missingExpectedNodes.removeAll(actualNodeKeys);

        double nodeRecall = testCase.expectedNodeKeys().isEmpty()
            ? 1.0
            : 1.0 - (double) missingExpectedNodes.size() / testCase.expectedNodeKeys().size();

        // 计算边召回率
        Set<String> missingExpectedEdges = new HashSet<>(testCase.expectedEdgeKeys());
        missingExpectedEdges.removeAll(actualEdgeKeys);

        double edgeRecall = testCase.expectedEdgeKeys().isEmpty()
            ? 1.0
            : 1.0 - (double) missingExpectedEdges.size() / testCase.expectedEdgeKeys().size();

        // 计算禁止边出现率
        Set<String> forbiddenEdgesFound = new HashSet<>(testCase.forbiddenEdgeKeys());
        forbiddenEdgesFound.retainAll(actualEdgeKeys);

        double forbiddenEdgeRate = actualEdgeKeys.isEmpty()
            ? 0.0
            : (double) forbiddenEdgesFound.size() / actualEdgeKeys.size();

        log.info("Benchmark score: caseId={}, nodeRecall={}, edgeRecall={}, forbiddenEdgeRate={}",
            testCase.name(), nodeRecall, edgeRecall, forbiddenEdgeRate);

        return new GraphifyBenchmarkResult(
            testCase.name(),
            nodeRecall, edgeRecall, forbiddenEdgeRate,
            missingExpectedNodes, missingExpectedEdges, forbiddenEdgesFound);
    }
}
