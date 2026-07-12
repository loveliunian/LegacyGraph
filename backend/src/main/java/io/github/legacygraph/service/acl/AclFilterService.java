package io.github.legacygraph.service.acl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * G-04: AclFilterService — 图谱查询结果的 ACL 过滤服务。
 * <p>在查询返回后对节点/边按用户权限过滤，确保用户只能看到有权限的数据。
 * 管理员跳过过滤。</p>
 *
 * <p>S3-T8: 注入 Micrometer {@link MeterRegistry}，对 filterNodes/filterEdges 采集耗时（Timer）
 * 与过滤计数（Counter），便于监控 ACL 过滤对查询延迟的影响。registry 缺失时静默降级。</p>
 */
@Slf4j
@Service
public class AclFilterService {

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /**
     * 过滤节点列表 — 按节点的 acl 字段（如有）与用户权限匹配。
     *
     * @param nodes   原始节点列表
     * @param context 访问上下文
     * @return 过滤后的节点列表
     */
    public List<Map<String, Object>> filterNodes(List<Map<String, Object>> nodes, AccessContext context) {
        if (context == null || context.isAdmin()) {
            incrementCounter("acl.filter.skipped", "reason", context == null ? "null_context" : "admin");
            return nodes;
        }
        Timer.Sample sample = startTimer();
        List<Map<String, Object>> filtered = nodes.stream()
                .filter(node -> hasAccess(node, context))
                .collect(Collectors.toList());
        stopTimer(sample, "acl.filter.nodes.duration");
        incrementCounter("acl.filter.nodes.filtered", "removed", String.valueOf(nodes.size() - filtered.size()));
        return filtered;
    }

    /**
     * 过滤边列表 — 两端节点都必须有权限。
     */
    public List<Map<String, Object>> filterEdges(List<Map<String, Object>> edges,
                                                  List<Map<String, Object>> filteredNodes,
                                                  AccessContext context) {
        if (context == null || context.isAdmin()) {
            incrementCounter("acl.filter.skipped", "reason", context == null ? "null_context" : "admin");
            return edges;
        }
        Timer.Sample sample = startTimer();
        java.util.Set<String> nodeIds = filteredNodes.stream()
                .map(n -> (String) n.get("id"))
                .collect(Collectors.toSet());
        List<Map<String, Object>> filtered = edges.stream()
                .filter(e -> nodeIds.contains(e.get("source")) && nodeIds.contains(e.get("target")))
                .collect(Collectors.toList());
        stopTimer(sample, "acl.filter.edges.duration");
        incrementCounter("acl.filter.edges.filtered", "removed", String.valueOf(edges.size() - filtered.size()));
        return filtered;
    }

    private boolean hasAccess(Map<String, Object> node, AccessContext context) {
        String requiredPermission = (String) node.get("requiredPermission");
        if (requiredPermission == null || requiredPermission.isBlank()) {
            return true; // 无权限标记的节点默认可见
        }
        return context.getPermissions() != null && context.getPermissions().contains(requiredPermission);
    }

    // S3-T8: Micrometer 采样辅助方法 — registry 缺失时静默降级

    private Timer.Sample startTimer() {
        return meterRegistry != null ? Timer.start(meterRegistry) : null;
    }

    private void stopTimer(Timer.Sample sample, String name) {
        if (sample != null && meterRegistry != null) {
            sample.stop(Timer.builder(name)
                    .description("ACL filter duration")
                    .register(meterRegistry));
        }
    }

    private void incrementCounter(String name, String tagKey, String tagValue) {
        if (meterRegistry != null) {
            Counter.builder(name)
                    .tag(tagKey, tagValue)
                    .register(meterRegistry)
                    .increment();
        }
    }
}
