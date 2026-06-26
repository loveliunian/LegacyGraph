package io.github.legacygraph.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 生成测试用例 - 对应文档中的 GeneratedTestCase JSON Schema
 */
@Data
public class GeneratedTestCase {

    private String featureKey;
    private String caseName;
    private CaseType caseType;
    private List<String> preconditions;
    private List<String> steps;
    private Map<String, Object> request;
    private List<TestCaseAssertion> assertions;
    private List<String> needHumanInput;

    public enum CaseType {
        API,
        E2E,
        DB,
        HYBRID
    }

    @Data
    public static class TestCaseAssertion {
        private AssertionType type;
        private String expression;
    }

    public enum AssertionType {
        HTTP,
        JSON_PATH,
        SQL,
        STATE,
        UI
    }
}
