package io.github.legacygraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用例生成结果 — 对应 test-case-generation 模板输出的 {"testCases": [...]} 结构。
 *
 * <p>模板单次调用返回多个场景（正常/异常/边界），由本包装类承载，
 * 解决"模板输出数组 vs Agent 期望单对象"的契约不一致问题。</p>
 */
@Data
public class TestCaseGenerationResult {

    private List<GeneratedTestCase> testCases = new ArrayList<>();
}
