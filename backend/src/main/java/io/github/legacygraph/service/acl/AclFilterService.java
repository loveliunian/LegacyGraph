package io.github.legacygraph.service.acl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * G-04: AclFilterService — 图谱查询结果的 ACL 过滤服务。
 * <p>在查询返回后对节点/边按用户权限过滤，确保用户只能看到有权限的数据。
 * 管理员跳过过滤。</p>
 */
@Slf4j
@Service
public class AclFilterService {

    /**
     * 过滤节点列表 — 按节点的 acl 字段（如有）与用户权限匹配。
     *
     * @param nodes   原始节点列表
     * @param context 访问上下文
     * @return 过滤后的节点列表
     */
    public List<Map<String, Object>> filterNodes(List<Map<String, Object>> nodes, AccessContext context) {
        if (context == null || context.isAdmin()) {
            return nodes;
        }
        return nodes.stream()
                .filter(node -> hasAccess(node, context))
                .collect(Collectors.toList());
    }

    /**
     * 过滤边列表 — 两端节点都必须有权限。
     */
    public List<Map<String, Object>> filterEdges(List<Map<String, Object>> edges,
                                                  List<Map<String, Object>> filteredNodes,
                                                  AccessContext context) {
        if (context == null || context.isAdmin()) {
            return edges;
        }
        java.util.Set<String> nodeIds = filteredNodes.stream()
                .map(n -> (String) n.get("id"))
                .collect(Collectors.toSet());
        return edges.stream()
                .filter(e -> nodeIds.contains(e.get("source")) && nodeIds.contains(e.get("target")))
                .collect(Collectors.toList());
    }

    private boolean hasAccess(Map<String, Object> node, AccessContext context) {
        String requiredPermission = (String) node.get("requiredPermission");
        if (requiredPermission == null || requiredPermission.isBlank()) {
            return true; // 无权限标记的节点默认可见
        }
        return context.getPermissions() != null && context.getPermissions().contains(requiredPermission);
    }
}
