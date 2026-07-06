package io.github.legacygraph.federation;

/**
 * 联邦图谱作用域，用于标识跨项目、跨仓库的图谱上下文
 */
public record FederatedGraphScope(
    String tenantId,        // 租户ID
    String systemId,        // 系统ID
    String projectId,       // 项目ID
    String repositoryUrl,   // 仓库URL
    String branchName       // 分支名称
) {
    public FederatedGraphScope {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (systemId == null || systemId.isBlank()) {
            throw new IllegalArgumentException("systemId 不能为空");
        }
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw new IllegalArgumentException("repositoryUrl 不能为空");
        }
        if (branchName == null || branchName.isBlank()) {
            branchName = "main";
        }
    }
    
    /**
     * 生成唯一的作用域标识符
     */
    public String toScopeKey() {
        return String.format("%s:%s:%s:%s:%s", 
            tenantId, systemId, projectId, repositoryUrl, branchName);
    }
}
