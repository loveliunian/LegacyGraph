package io.github.legacygraph.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建项目请求DTO
 */
@Data
@Schema(description = "创建项目请求")
public class CreateProjectRequest {

    @NotBlank(message = "项目编码不能为空")
    @Size(max = 50, message = "项目编码长度不能超过50个字符")
    @Schema(description = "项目编码，唯一标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "legacy-graph")
    private String projectCode;

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 100, message = "项目名称长度不能超过100个字符")
    @Schema(description = "项目名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "遗留系统知识图谱")
    private String projectName;

    @Size(max = 500, message = "项目描述长度不能超过500个字符")
    @Schema(description = "项目描述", example = "用于分析遗留系统的知识图谱项目")
    private String description;

    @Schema(description = "项目类型，如JAVA、COBOL等", example = "JAVA")
    private String projectType;

    @Schema(description = "代码仓库地址", example = "https://github.com/example/project.git")
    private String repoUrl;

    @Schema(description = "默认分支", example = "main")
    private String defaultBranch;

    @Schema(description = "负责人", example = "张三")
    private String owner;
}
