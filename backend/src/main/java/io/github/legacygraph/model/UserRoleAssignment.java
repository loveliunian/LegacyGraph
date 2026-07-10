package io.github.legacygraph.model;

import lombok.Builder;
import lombok.Data;

/**
 * 用户-角色关联（来自 DB sys_user_role 表或代码 SecurityConfig 配置）。
 * <p>用于 GraphBuilder.buildRbacUserGraph 构建 User 节点和 ASSIGNED_TO 边。</p>
 */
@Data
@Builder
public class UserRoleAssignment {
    /** 用户名（sys_user.username 或 User.builder().username()） */
    private String userName;
    /** 角色名（sys_role.role_key 或 .roles() 参数） */
    private String roleName;
    /** 来源路径（SQL 表名或 Java 文件路径） */
    private String sourcePath;
    /** 来源类型（DB_SCAN / CODE_AST），默认 DB_SCAN */
    @Builder.Default
    private String sourceType = "DB_SCAN";
}
