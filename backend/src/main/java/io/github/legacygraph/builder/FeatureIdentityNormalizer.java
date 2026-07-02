package io.github.legacygraph.builder;

import org.springframework.stereotype.Component;

/**
 * Feature 身份归一化器。
 *
 * <p>业务/功能节点的 nodeKey 此前在三处生成规则不一（流程名、{@code "feature:"+名称}、
 * {@code "code-feature:"+名称}），导致同一功能因来源/写法不同而产生多个 Feature 节点。
 * 本组件把功能名归一为稳定 key，保证跨来源（文档 AI / 代码 AI / 流程派生）去重一致。</p>
 *
 * <p>归一化规则：trim → 转小写 → 全角/中文标点转半角空白 → 去公共前缀（feature:/功能:等）
 * → 压缩连续空白 → 去首尾分隔符。归一化只用于计算 nodeKey，展示名仍保留原文。</p>
 */
@Component
public class FeatureIdentityNormalizer {

    /** Feature nodeKey 统一前缀，便于按类型检索。 */
    public static final String FEATURE_KEY_PREFIX = "feature:";

    /**
     * 由功能显示名计算稳定的 Feature nodeKey。
     *
     * @param rawName 原始功能名（可能带来源前缀、大小写、标点差异）
     * @return 形如 {@code feature:订单提交} 的归一化 key；名称为空时返回 null
     */
    public String toFeatureKey(String rawName) {
        String normalized = normalizeName(rawName);
        if (normalized.isEmpty()) {
            return null;
        }
        return FEATURE_KEY_PREFIX + normalized;
    }

    /**
     * 归一化功能名（用于比较与生成 key，不用于展示）。
     */
    public String normalizeName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String n = rawName.trim().toLowerCase();

        // 去掉常见来源/类型前缀（含中英文）
        for (String prefix : new String[]{"feature:", "code-feature:", "code_feature:", "功能:", "功能点:", "特性:"}) {
            if (n.startsWith(prefix)) {
                n = n.substring(prefix.length()).trim();
                break;
            }
        }

        // 中文/全角标点归一为空格，便于跨写法匹配
        n = n.replaceAll("[，、。；：/\\\\|_\\-]+", " ");
        // 压缩连续空白
        n = n.replaceAll("\\s+", " ").trim();
        return n;
    }
}
