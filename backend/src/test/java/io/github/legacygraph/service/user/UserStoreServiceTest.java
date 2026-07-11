package io.github.legacygraph.service.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UserStoreService} / {@link FuzzyReviewerRoleResolver} 单测（G-11 §13.4 强校验）。
 * <p>锁定行为：当前实现是模糊匹配（Phase 1）；未来 Phase 2 接入 LDAP/JWT 后，
 * 本测试需相应调整，但 {@code hasRole(reviewer, "LEAD", "PM")} 的调用契约不变。</p>
 */
class UserStoreServiceTest {

    private final UserStoreService userStoreService = new UserStoreService();

    @Test
    void hasRole_leadKeyword_matchesLead() {
        assertTrue(userStoreService.hasRole("Alice (LEAD)", "LEAD", "PM"));
    }

    @Test
    void hasRole_pmKeyword_matchesPm() {
        assertTrue(userStoreService.hasRole("bob.pm", "LEAD", "PM"));
    }

    @Test
    void hasRole_caseInsensitive() {
        assertTrue(userStoreService.hasRole("Charlie-lead", "LEAD", "PM"));
    }

    @Test
    void hasRole_nullReviewer_returnsFalse() {
        assertFalse(userStoreService.hasRole(null, "LEAD", "PM"));
    }

    @Test
    void hasRole_blankReviewer_returnsFalse() {
        assertFalse(userStoreService.hasRole("   ", "LEAD", "PM"));
    }

    @Test
    void hasRole_noKeyword_returnsFalse() {
        assertFalse(userStoreService.hasRole("developer", "LEAD", "PM"));
    }

    @Test
    void hasRole_noRequiredRoles_returnsTrue() {
        // 空必需集合视为"通过"（与 hasAnyRole 语义一致）
        assertTrue(userStoreService.hasRole("developer"));
    }

    @Test
    void hasRole_zeroRolesAreNeverMatched() {
        // 空数组变参语义上等价于"未指定任何角色"，按 hasAnyRole 返回 true
        assertTrue(userStoreService.hasRole("developer", (String[]) null));
    }

    @Test
    void resolveRoles_returnsAllMatchedKeywords() {
        var roles = userStoreService.resolveRoles("Alice (LEAD & ARCHITECT)");
        assertTrue(roles.contains("LEAD"));
        assertTrue(roles.contains("ARCHITECT"));
    }

    @Test
    void resolveRoles_emptyForNull() {
        assertTrue(userStoreService.resolveRoles(null).isEmpty());
    }

    @Test
    void fuzzyResolver_implementsHasAnyRoleViaInterface() {
        ReviewerRoleResolver resolver = new FuzzyReviewerRoleResolver();
        assertTrue(resolver.hasAnyRole("admin-role", java.util.List.of("LEAD", "ADMIN")));
        assertFalse(resolver.hasAnyRole("developer", java.util.List.of("LEAD", "PM")));
    }
}