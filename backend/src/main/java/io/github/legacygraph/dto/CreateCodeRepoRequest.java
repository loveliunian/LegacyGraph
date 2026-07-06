package io.github.legacygraph.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateCodeRepoRequest {

    @NotBlank(message = "仓库名称不能为空")
    @Size(max = 100, message = "仓库名称长度不能超过100个字符")
    private String repoName;

    @NotBlank(message = "仓库类型不能为空")
    private String repoType;

    @Pattern(regexp = "^(http|https|git)://.*$", message = "Git地址格式不正确")
    private String gitUrl;

    private String branchName;

    private String authType;

    private String username;

    private String password;

    private String token;

    private String sshKey;

    private String includePattern;

    private String excludePattern;

    /**
     * 全栈项目-后端子路径 (仅 repoType=FULLSTACK 时使用)
     */
    private String backendSubPath;

    /**
     * 全栈项目-前端子路径 (仅 repoType=FULLSTACK 时使用)
     */
    private String frontendSubPath;

    /**
     * 本地代码路径（可选）。
     * 如果指定，扫描时直接使用该路径，无需从 Git 拉取。
     */
    private String localPath;
}
