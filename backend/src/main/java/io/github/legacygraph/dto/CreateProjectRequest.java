package io.github.legacygraph.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建项目请求DTO
 */
@Data
public class CreateProjectRequest {

    @NotBlank(message = "项目编码不能为空")
    @Size(max = 50, message = "项目编码长度不能超过50个字符")
    private String projectCode;

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 100, message = "项目名称长度不能超过100个字符")
    private String projectName;

    @Size(max = 500, message = "项目描述长度不能超过500个字符")
    private String description;

    private String projectType;

    private String repoUrl;

    private String defaultBranch;

    private String owner;
}
