package io.github.legacygraph.dto;

import lombok.Data;

import java.util.List;

/**
 * 执行测试请求DTO
 */
@Data
public class StartTestRunRequest {

    private String versionId;
    private List<String> caseIds;
    private String environment;
}
