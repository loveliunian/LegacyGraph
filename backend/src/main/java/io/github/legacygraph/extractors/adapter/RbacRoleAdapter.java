package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.RbacRoleExtractor;
import io.github.legacygraph.extractors.RbacUserAssignmentExtractor;
import io.github.legacygraph.model.NodeExtractionResult;
import io.github.legacygraph.model.UserRoleAssignment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RBAC 角色扫描适配器。
 * <p>从 Java 源码注解（@PreAuthorize/@Secured/@RequiresRoles/@RolesAllowed）提取 Role/Permission 节点，
 * 构建 Role --GRANTS--> Permission 边（委托 GraphBuilder.buildRbacRoleGraph）。</p>
 * <p>同时尝试从 sys_user_role 关联表提取 Role→User 关联，构建 Role --ASSIGNED_TO--> User 边
 * （委托 GraphBuilder.buildRbacUserGraph）。每个 projectId+versionId 只查询一次数据库。</p>
 */
@Slf4j
@Component
public class RbacRoleAdapter implements ExtractionAdapter {

    private final RbacRoleExtractor extractor;
    private final RbacUserAssignmentExtractor userAssignmentExtractor;
    private final GraphBuilder graphBuilder;
    /** 可选注入：目标项目数据库可能未配置或 sys_user_role 表不存在 */
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    /** 记录已处理过 DB User 图谱的 projectId+versionId，避免每个 Java 文件都重复查询 */
    private final ConcurrentHashMap<String, Boolean> userGraphBuilt = new ConcurrentHashMap<>();

    public RbacRoleAdapter(RbacRoleExtractor extractor,
                           RbacUserAssignmentExtractor userAssignmentExtractor,
                           GraphBuilder graphBuilder) {
        this.extractor = extractor;
        this.userAssignmentExtractor = userAssignmentExtractor;
        this.graphBuilder = graphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        String path = asset.getRelativePath();
        return path != null && path.endsWith(".java") && path.contains("src/main/java");
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        try {
            List<NodeExtractionResult> results = extractor.extract(asset.getFile().toFile());
            // 同时从代码中提取 User-Role 分配（SecurityConfig 类的 User.builder().roles() 模式）
            List<UserRoleAssignment> codeUserRoles = userAssignmentExtractor.extract(asset.getFile().toFile());
            if (!codeUserRoles.isEmpty()) {
                graphBuilder.buildRbacUserGraph(context.getProjectId(), context.getVersionId(), codeUserRoles);
                log.info("Scanned {} user-role assignments from code: {}", codeUserRoles.size(), asset.getRelativePath());
            }

            if (!results.isEmpty()) {
                graphBuilder.buildRbacRoleGraph(context.getProjectId(), context.getVersionId(), results);
                log.info("Scanned {} RBAC roles from {}", results.size(), asset.getRelativePath());

                // 尝试从 sys_user_role 表提取 User-Role 关联（每个 projectId+versionId 只查一次）
                tryBuildUserGraph(context);

                return ExtractionResult.builder()
                        .processedAssets(1)
                        .nodeCount(results.size())
                        .summary("Scanned " + results.size() + " RBAC roles, " + codeUserRoles.size() + " user assignments")
                        .build();
            }
            if (!codeUserRoles.isEmpty()) {
                return ExtractionResult.builder()
                        .processedAssets(1)
                        .nodeCount(codeUserRoles.size())
                        .summary("Scanned " + codeUserRoles.size() + " user-role assignments from code")
                        .build();
            }
        } catch (Exception e) {
            log.warn("Failed to extract RBAC roles from {}: {}", asset.getRelativePath(), e.getMessage());
        }
        return ExtractionResult.builder().processedAssets(0).build();
    }

    /**
     * 尝试从 sys_user_role 关联表提取 User-Role 关联并构建 ASSIGNED_TO 边。
     * <p>如果 JdbcTemplate 不可用或 sys_user_role 表不存在，静默跳过，不影响后续扫描。</p>
     */
    private void tryBuildUserGraph(ScanContext context) {
        String scanKey = context.getProjectId() + ":" + context.getVersionId();
        if (userGraphBuilt.putIfAbsent(scanKey, Boolean.TRUE) != null) {
            return; // 已处理过，跳过
        }
        if (jdbcTemplate == null) {
            return; // 无数据库连接，跳过
        }
        try {
            List<UserRoleAssignment> userRoles = extractUserRolesFromDb();
            if (!userRoles.isEmpty()) {
                graphBuilder.buildRbacUserGraph(context.getProjectId(), context.getVersionId(), userRoles);
                log.info("Scanned {} user-role assignments from sys_user_role", userRoles.size());
            }
        } catch (Exception e) {
            // sys_user_role 表可能不存在（非 RuoYi 框架项目），静默跳过
            log.debug("Skip sys_user_role scan (table may not exist): {}", e.getMessage());
        }
    }

    /**
     * 从 sys_user_role 关联表查询 User-Role 关联。
     * <p>兼容 RuoYi/Ruoyi-Vue 框架的 sys_user/sys_role/sys_user_role 三表结构。</p>
     */
    private List<UserRoleAssignment> extractUserRolesFromDb() {
        String sql = "SELECT u.user_name, r.role_key FROM sys_user_role sur "
                + "JOIN sys_user u ON sur.user_id = u.user_id "
                + "JOIN sys_role r ON sur.role_id = r.role_id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> UserRoleAssignment.builder()
                .userName(rs.getString("user_name"))
                .roleName(rs.getString("role_key"))
                .sourcePath("sys_user_role")
                .build());
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("RbacRoleAdapter")
                .languages(Set.of("java"))
                .fileTypes(Set.of("java"))
                .frameworks(Set.of("spring", "shiro", "sa-token"))
                .aiEnhanced(false)
                .priority(50)
                .build();
    }
}
