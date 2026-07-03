package io.github.legacygraph.tenant;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 多租户资源配额管理器（P3-5）
 * 管理每个租户/项目的资源配额：节点数、边数、向量数、Agent 调用次数等。
 */
@Component
public class TenantQuotaManager {

    private final Map<String, TenantQuota> quotas = new ConcurrentHashMap<>();

    /**
     * 获取项目配额
     */
    public TenantQuota getQuota(String projectId) {
        return quotas.computeIfAbsent(projectId, id -> TenantQuota.defaultQuota());
    }

    /**
     * 检查是否超出配额
     */
    public boolean checkQuota(String projectId, QuotaType type, long requested) {
        TenantQuota quota = getQuota(projectId);
        long current = quota.getUsage(type);
        long limit = quota.getLimit(type);
        return current + requested <= limit;
    }

    /**
     * 增加用量
     */
    public void incrementUsage(String projectId, QuotaType type, long delta) {
        TenantQuota quota = getQuota(projectId);
        quota.addUsage(type, delta);
    }

    /**
     * 配额类型
     */
    public enum QuotaType {
        NODES(100_000),
        EDGES(500_000),
        VECTORS(50_000),
        AGENT_CALLS(1_000),
        SCAN_VERSIONS(20);

        private final long defaultLimit;

        QuotaType(long defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public long getDefaultLimit() {
            return defaultLimit;
        }
    }

    /**
     * 租户配额
     */
    public static class TenantQuota {
        private final Map<QuotaType, Long> limits = new ConcurrentHashMap<>();
        private final Map<QuotaType, Long> usages = new ConcurrentHashMap<>();

        public static TenantQuota defaultQuota() {
            TenantQuota q = new TenantQuota();
            for (QuotaType type : QuotaType.values()) {
                q.limits.put(type, type.getDefaultLimit());
                q.usages.put(type, 0L);
            }
            return q;
        }

        public long getLimit(QuotaType type) {
            return limits.getOrDefault(type, type.getDefaultLimit());
        }

        public long getUsage(QuotaType type) {
            return usages.getOrDefault(type, 0L);
        }

        public void addUsage(QuotaType type, long delta) {
            usages.merge(type, delta, Long::sum);
        }

        public void setLimit(QuotaType type, long limit) {
            limits.put(type, limit);
        }

        public Map<QuotaType, Long> getLimits() {
            return Map.copyOf(limits);
        }

        public Map<QuotaType, Long> getUsages() {
            return Map.copyOf(usages);
        }
    }
}
