package io.github.legacygraph.dto.qa;

import java.util.List;

/**
 * 访问上下文 — 携带当前请求的安全主体信息，用于证据 ACL 校验和版本化缓存隔离。
 *
 * <p>principals 是用户持有的安全主体列表（如 {@code ["user:alice", "group:dev"]}），
 * 用于校验证据是否可被当前用户访问。team 表示用户所属团队，可用于粗粒度过滤。</p>
 *
 * @param principals 安全主体列表（不能为 null，无限制时为空列表）
 * @param team       用户所属团队（可为 null）
 */
public record AccessContext(List<String> principals, String team) {

    /** 公开访问上下文：无 ACL 限制，principals 为空。 */
    public static final AccessContext PUBLIC = new AccessContext(List.of(), null);

    /**
     * 构造方法 — 防御性拷贝 principals，避免外部修改。
     */
    public AccessContext {
        principals = principals == null ? List.of() : List.copyOf(principals);
    }

    /**
     * 计算 ACL 哈希 — 对 principals 排序后取哈希，用作版本化缓存隔离维度。
     * <p>相同 principals 集合（无论顺序）产出相同哈希，保证缓存可复用。</p>
     *
     * @return ACL 哈希字符串，principals 为空时返回 {@code "PUBLIC"}
     */
    public String aclHash() {
        if (principals.isEmpty()) {
            return "PUBLIC";
        }
        List<String> sorted = principals.stream().sorted().toList();
        return Integer.toHexString(sorted.hashCode());
    }
}
