package io.github.legacygraph.dto;

import lombok.Data;

import java.util.List;

/**
 * 生成测试用例请求DTO
 */
@Data
public class GenerateTestCasesRequest {

    private String versionId;
    private Scope scope;

    @Data
    public static class Scope {
        private List<String> nodeTypes;
        private List<String> priority;
    }
}
