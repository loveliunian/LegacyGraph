package io.github.legacygraph.governance;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Graphify 访问策略控制。
 * <p>
 * 定义不同角色对 Graphify 操作的权限：
 * <ul>
 *   <li>GRAPHIFY_ADMIN: 可以运行导入、重试作业、回滚作业、修改配置</li>
 *   <li>GRAPH_REVIEWER: 可以审核候选边，但不能运行导入和查看原始代码片段</li>
 *   <li>GRAPH_EVIDENCE_VIEWER: 可以查看原始 evidence 内容和未脱敏 source path</li>
 *   <li>GRAPH_VIEWER: 只能查看脱敏后的图谱信息</li>
 * </ul>
 * </p>
 */
@Component
public class GraphifyAccessPolicy {

    /**
     * 检查是否可以运行 Graphify 导入。
     *
     * @param userRoles 用户角色集合
     * @return true 如果允许
     */
    public boolean canRunImport(Set<Role> userRoles) {
        return userRoles.contains(Role.GRAPHIFY_ADMIN);
    }

    /**
     * 检查是否可以重试失败的导入作业。
     *
     * @param userRoles 用户角色集合
     * @return true 如果允许
     */
    public boolean canRetryJob(Set<Role> userRoles) {
        return userRoles.contains(Role.GRAPHIFY_ADMIN);
    }

    /**
     * 检查是否可以回滚导入作业。
     *
     * @param userRoles 用户角色集合
     * @return true 如果允许
     */
    public boolean canRollbackJob(Set<Role> userRoles) {
        return userRoles.contains(Role.GRAPHIFY_ADMIN);
    }

    /**
     * 检查是否可以修改 Graphify 配置。
     *
     * @param userRoles 用户角色集合
     * @return true 如果允许
     */
    public boolean canModifyConfig(Set<Role> userRoles) {
        return userRoles.contains(Role.GRAPHIFY_ADMIN);
    }

    /**
     * 检查是否可以审核候选边。
     *
     * @param userRoles 用户角色集合
     * @return true 如果允许
     */
    public boolean canReviewCandidate(Set<Role> userRoles) {
        return userRoles.contains(Role.GRAPH_REVIEWER) || userRoles.contains(Role.GRAPHIFY_ADMIN);
    }

    /**
     * 检查是否可以查看原始 evidence 内容（未脱敏）。
     *
     * @param userRoles 用户角色集合
     * @return true 如果允许
     */
    public boolean canViewRawEvidence(Set<Role> userRoles) {
        return userRoles.contains(Role.GRAPH_EVIDENCE_VIEWER) || userRoles.contains(Role.GRAPHIFY_ADMIN);
    }

    /**
     * 检查是否可以查看脱敏后的图谱信息。
     *
     * @param userRoles 用户角色集合
     * @return true 如果允许
     */
    public boolean canViewGraph(Set<Role> userRoles) {
        return userRoles.contains(Role.GRAPH_VIEWER) 
            || userRoles.contains(Role.GRAPH_REVIEWER)
            || userRoles.contains(Role.GRAPH_EVIDENCE_VIEWER)
            || userRoles.contains(Role.GRAPHIFY_ADMIN);
    }
}
