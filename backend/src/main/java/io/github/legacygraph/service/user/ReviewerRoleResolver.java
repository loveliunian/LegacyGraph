package io.github.legacygraph.service.user;

import java.util.Collection;
import java.util.List;

/**
 * 用户角色解析器（G-11 §13.4 接口约定）。
 * <p>统一替代之前各 Controller 中零散的 {@code isLeadOrPm(String reviewer)} 模糊匹配。
 * 实现策略由配置 / 部署环境决定：</p>
 *
 * <ul>
 *   <li><b>Phase 1（当前实现）</b>：{@link FuzzyReviewerRoleResolver}，从 reviewer 字符串里大小写不敏感
 *       地匹配角色关键字。用于尚未接入统一身份系统的过渡期，与原行为兼容。</li>
 *   <li><b>Phase 2（未来接入）</b>：从 JWT 拿 username → {@code lg_user} 表查角色；
 *       或对接企业 LDAP / SSO 的 group claim。</li>
 * </ul>
 *
 * <p>Controller / Service 应当通过注入 {@link UserStoreService} 使用，避免再写各自的字符串匹配。</p>
 *
 * @see io.github.legacygraph.service.user.UserStoreService
 */
public interface ReviewerRoleResolver {

    /**
     * 解析 reviewer 的角色集合。
     *
     * @param reviewer reviewer 名称或标识（不同实现解读不同）
     * @return 角色字符串列表；未识别时为空列表（永不为 null）
     */
    List<String> resolveRoles(String reviewer);

    /**
     * 是否拥有任一给定角色（G-11 §13.4 LEAD/PM 强校验的目标接口）。
     *
     * @param reviewer     reviewer 标识
     * @param requiredRoles 角色集合（任一命中即返回 true）
     * @return true 当且仅当 reviewer 拥有任一 requiredRoles 角色
     */
    default boolean hasAnyRole(String reviewer, Collection<String> requiredRoles) {
        if (requiredRoles == null || requiredRoles.isEmpty()) {
            return true;
        }
        List<String> actual = resolveRoles(reviewer);
        if (actual == null || actual.isEmpty()) {
            return false;
        }
        return actual.stream().anyMatch(r -> requiredRoles.stream()
                .anyMatch(rr -> rr != null && rr.equalsIgnoreCase(r)));
    }
}