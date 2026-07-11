package io.github.legacygraph.service.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 过渡期 reviewer 角色解析器（G-11 §13.4 Phase 1）。
 * <p>在尚未接入统一身份系统前，将 reviewer 字符串视为可能含角色段的自由文本，
 * 大小写不敏感地匹配 LEAD / PM / ADMIN / REVIEWER 等关键字。
 * 与原 {@code SolutionController.isLeadOrPm} 行为兼容。</p>
 *
 * <p>未来替换为：JWT 解析 → username → {@code lg_user} 表 / LDAP 查询角色列表。</p>
 */
public class FuzzyReviewerRoleResolver implements ReviewerRoleResolver {

    /** 角色关键字：关键词出现在 reviewer 字符串中即视为拥有该角色 */
    private static final List<String> ROLE_KEYWORDS = List.of(
            "LEAD", "PM", "ADMIN", "REVIEWER", "ARCHITECT", "OWNER", "MAINTAINER"
    );

    @Override
    public List<String> resolveRoles(String reviewer) {
        List<String> roles = new ArrayList<>();
        if (reviewer == null || reviewer.isBlank()) {
            return roles;
        }
        String upper = reviewer.toUpperCase(Locale.ROOT);
        for (String keyword : ROLE_KEYWORDS) {
            if (upper.contains(keyword)) {
                roles.add(keyword);
            }
        }
        return roles;
    }
}