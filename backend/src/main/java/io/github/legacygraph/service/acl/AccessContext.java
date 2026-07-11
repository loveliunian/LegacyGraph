package io.github.legacygraph.service.acl;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * G-04: AccessContext — 请求级访问上下文，承载用户身份和权限信息。
 * <p>从 JWT 注入 principal，用于图谱查询/检索的 ACL 过滤。
 * 缓存键显式包含 {@code aclHash} 以确保不同权限的用户不命中彼此的缓存。</p>
 */
@Data
@Builder
public class AccessContext {

    /** 用户 ID */
    private String userId;

    /** 用户名 */
    private String username;

    /** 角色 ID 集合 */
    private Set<String> roleIds;

    /** 权限标识集合（如 graph:read, scan:create） */
    private Set<String> permissions;

    /** 是否为管理员（跳过 ACL 过滤） */
    private boolean admin;

    /**
     * ACL 哈希值 — 用于缓存键隔离。
     * 基于 roleIds + permissions 的有序拼接做哈希，确保相同权限集的用户可共享缓存。
     */
    public String aclHash() {
        if (admin) return "admin";
        if (roleIds == null && permissions == null) return "empty";
        StringBuilder sb = new StringBuilder();
        if (roleIds != null) {
            roleIds.stream().sorted().forEach(sb::append);
        }
        if (permissions != null) {
            permissions.stream().sorted().forEach(sb::append);
        }
        return Integer.toHexString(sb.toString().hashCode());
    }
}
