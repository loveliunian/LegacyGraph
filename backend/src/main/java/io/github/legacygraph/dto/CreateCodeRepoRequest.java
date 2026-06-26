package io.github.legacygraph.dto;

import lombok.Data;

@Data
public class CreateCodeRepoRequest {

    private String repoName;

    private String repoType;

    private String gitUrl;

    private String branchName;

    private String authType;

    private String username;

    private String password;

    private String token;

    private String sshKey;

    private String includePattern;

    private String excludePattern;
}
