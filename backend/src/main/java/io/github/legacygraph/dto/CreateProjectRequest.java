package io.github.legacygraph.dto;

import lombok.Data;

/**
 * 创建项目请求DTO
 */
@Data
public class CreateProjectRequest {

    private String projectCode;
    private String projectName;
    private String description;
    private String projectType;
    private String repoUrl;
    private String defaultBranch;
    private String owner;
}
