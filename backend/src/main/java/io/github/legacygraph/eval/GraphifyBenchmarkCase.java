package io.github.legacygraph.eval;

import java.util.Set;

/**
 * Graphify benchmark 测试用例。
 * 
 * @param name              用例名称（如 spring-vue-sql, dynamic-sql）
 * @param expectedNodeKeys  期望导入的节点键集合
 * @param expectedEdgeKeys  期望导入的边键集合
 * @param forbiddenEdgeKeys 禁止出现的边键集合（用于检测污染）
 */
public record GraphifyBenchmarkCase(
    String name,
    Set<String> expectedNodeKeys,
    Set<String> expectedEdgeKeys,
    Set<String> forbiddenEdgeKeys
) {
    public GraphifyBenchmarkCase {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (expectedNodeKeys == null) expectedNodeKeys = Set.of();
        if (expectedEdgeKeys == null) expectedEdgeKeys = Set.of();
        if (forbiddenEdgeKeys == null) forbiddenEdgeKeys = Set.of();
    }
}
