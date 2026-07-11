package io.github.legacygraph.service.user;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 用户角色门面（G-11 §13.4 强校验入口）。
 * <p>提供与前端 {@code userStore.hasRole(reviewer, ...)} 等价的接口，
 * Controller / Service 只需依赖本类，无需关心具体解析器实现。</p>
 *
 * <p>当前默认实现 {@link FuzzyReviewerRoleResolver}；接入统一身份系统后只需
 * 把 {@code resolver} 字段替换为新实现即可，调用方零改动。</p>
 *
 * @see ReviewerRoleResolver
 */
@Slf4j
@Service
public class UserStoreService {

    /** 当前解析器实现（Phase 1：模糊匹配；Phase 2：替换为 LDAP/JWT） */
    private final ReviewerRoleResolver resolver;

    public UserStoreService() {
        this.resolver = new FuzzyReviewerRoleResolver();
        log.info("UserStoreService initialized with resolver: {}", resolver.getClass().getSimpleName());
    }

    /** 测试/扩展入口：允许显式注入不同实现 */
    public UserStoreService(ReviewerRoleResolver resolver) {
        this.resolver = resolver != null ? resolver : new FuzzyReviewerRoleResolver();
        log.info("UserStoreService initialized with resolver: {}", this.resolver.getClass().getSimpleName());
    }

    /**
     * reviewer 是否拥有任一必需角色。
     *
     * @param reviewer      reviewer 标识
     * @param requiredRoles 必需角色（变参），任一命中即通过
     * @return true 当且仅当 reviewer 拥有任一 requiredRoles 角色
     */
    public boolean hasRole(String reviewer, String... requiredRoles) {
        if (requiredRoles == null || requiredRoles.length == 0) {
            return true;
        }
        return resolver.hasAnyRole(reviewer, Arrays.asList(requiredRoles));
    }

    /**
     * 解析 reviewer 的角色列表。
     */
    public List<String> resolveRoles(String reviewer) {
        return resolver.resolveRoles(reviewer);
    }
}