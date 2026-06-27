package io.github.legacygraph.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 执行测试请求DTO
 */
@Data
public class StartTestRunRequest {

    @NotBlank(message = "版本ID不能为空")
    private String versionId;

    @NotEmpty(message = "测试用例ID列表不能为空")
    private List<String> caseIds;

    private String environment;
}
