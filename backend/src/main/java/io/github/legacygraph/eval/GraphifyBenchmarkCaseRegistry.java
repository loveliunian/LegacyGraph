package io.github.legacygraph.eval;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Graphify benchmark 用例注册表。
 * <p>
 * 当前仓库未内置 benchmark 样例，默认返回空列表。新增样例时在此返回固定样本，
 * 用例的 {@code expectedNodeKeys}/{@code expectedEdgeKeys} 应来自已知样本项目的期望图谱。
 * {@link GraphifyQualityService} 在无用例时会退化为基于实际导入数据的存在性评估，
 * 因此空注册表不影响质量接口可用。
 * </p>
 */
@Component
public class GraphifyBenchmarkCaseRegistry {

    /**
     * 返回当前注册的 benchmark 用例。
     *
     * @return 用例列表，可能为空
     */
    public List<GraphifyBenchmarkCase> cases() {
        return List.of();
    }
}
