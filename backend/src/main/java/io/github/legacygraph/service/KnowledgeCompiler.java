package io.github.legacygraph.service;

import io.github.legacygraph.dto.claim.ClaimCompileIssue;
import io.github.legacygraph.dto.claim.CompileOptions;
import io.github.legacygraph.dto.claim.CompiledGraphProjection;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.entity.KnowledgeClaim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识编译器 — 将 KnowledgeClaim 编译为图谱节点边投影。
 * <p>
 * 第一版为 dry-run：只输出节点边声明和问题，不实际写入 Neo4j。
 * 后续版本可接入 EvidenceGraphWriter 完成"从 Claim 到图谱"的主链路切换。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeCompiler {

    private final KnowledgeClaimService claimService;

    /**
     * 谓词到图谱边类型的映射。
     */
    private static final Map<String, String> PREDICATE_TO_EDGE = Map.ofEntries(
            Map.entry("EXPOSED_BY", "EXPOSED_BY"),
            Map.entry("IMPLEMENTED_BY", "IMPLEMENTED_BY"),
            Map.entry("HANDLED_BY", "HANDLED_BY"),
            Map.entry("CALLS", "CALLS"),
            Map.entry("EXECUTES", "EXECUTES"),
            Map.entry("READS", "READS"),
            Map.entry("WRITES", "WRITES"),
            Map.entry("HAS_COLUMN", "HAS_COLUMN"),
            Map.entry("ENFORCES_RULE", "ENFORCES_RULE"),
            Map.entry("HAS_RULE", "HAS_RULE"),
            Map.entry("VERIFIED_BY", "VERIFIED_BY"),
            Map.entry("MAPS_TO", "MAPS_TO")
    );

    /**
     * 编译指定版本的 Claim 为图谱投影。
     */
    public CompiledGraphProjection compile(String projectId, String versionId, CompileOptions options) {
        List<KnowledgeClaim> claims = claimService.listClaims(
                projectId, versionId, null, null, null, null, 5000);

        // 过滤
        claims = filterClaims(claims, options);
        log.info("KnowledgeCompiler: compiling {} claims (after filter) for versionId={}", claims.size(), versionId);

        return compileClaims(claims, options);
    }

    /**
     * 直接从 Claim 列表编译。
     */
    public CompiledGraphProjection compileClaims(List<KnowledgeClaim> claims, CompileOptions options) {
        if (options == null) {
            options = CompileOptions.builder().build();
        }
        Map<String, GraphNodeClaim> nodeClaimsByIdentity = new LinkedHashMap<>();
        List<GraphEdgeClaim> edgeClaims = new ArrayList<>();
        List<ClaimCompileIssue> issues = new ArrayList<>();
        int compiled = 0;
        int skipped = 0;

        // 按 subjectKey 分组做同义节点检测
        Map<String, Set<String>> nodeTypesByKey = new HashMap<>();

        for (KnowledgeClaim claim : claims) {
            try {
                // 检查是否能编译为边
                String edgeType = PREDICATE_TO_EDGE.get(claim.getPredicate());
                if (edgeType != null) {
                    if (options.isIncludePending() || "CONFIRMED".equals(claim.getStatus())) {
                        addNodeClaim(nodeClaimsByIdentity, claim, claim.getSubjectType(), claim.getSubjectKey());
                        addNodeClaim(nodeClaimsByIdentity, claim, claim.getObjectType(), claim.getObjectKey());
                        GraphEdgeClaim edge = buildEdgeClaim(claim, edgeType);
                        edgeClaims.add(edge);
                        compiled++;
                    } else {
                        skipped++;
                    }
                }

                // 收集主体节点类型
                nodeTypesByKey.computeIfAbsent(claim.getSubjectKey(), k -> new HashSet<>())
                        .add(claim.getSubjectType());

                // 收集客体节点类型
                if (claim.getObjectKey() != null) {
                    nodeTypesByKey.computeIfAbsent(claim.getObjectKey(), k -> new HashSet<>())
                            .add(claim.getObjectType() != null ? claim.getObjectType() : "Unknown");
                }
            } catch (Exception e) {
                log.warn("Failed to compile claim {}: {}", claim.getId(), e.getMessage());
                skipped++;
            }
        }

        // 同义节点冲突检测：同一 key 出现多个类型
        for (var entry : nodeTypesByKey.entrySet()) {
            if (entry.getValue().size() > 1) {
                issues.add(ClaimCompileIssue.builder()
                        .issueType(ClaimCompileIssue.IssueType.TYPE_MISMATCH)
                        .subjectKey(entry.getKey())
                        .description("节点 key=" + entry.getKey() + " 被识别为多种类型: " + String.join(", ", entry.getValue()))
                        .suggestedAction("需人工确认节点类型后合并或拆分为不同节点")
                        .build());
            }
        }

        // 冲突检测：同一 subject+predicate 但不同 object
        Map<String, String> firstObjectByKey = new HashMap<>();
        for (KnowledgeClaim claim : claims) {
            if (!"CONFIRMED".equals(claim.getStatus()) && !"PENDING_CONFIRM".equals(claim.getStatus())) {
                continue;
            }
            String key = claim.getSubjectKey() + "|" + claim.getPredicate();
            String objectKey = claim.getObjectKey();
            if (objectKey == null) continue;

            String existing = firstObjectByKey.get(key);
            if (existing != null && !existing.equals(objectKey)) {
                issues.add(ClaimCompileIssue.builder()
                        .issueType(ClaimCompileIssue.IssueType.CONFLICT)
                        .subjectKey(claim.getSubjectKey())
                        .predicate(claim.getPredicate())
                        .description("同一主体+谓词映射到不同客体: " + existing + " vs " + objectKey)
                        .suggestedAction("需检查两条 Claim 的证据，选择正确的映射或标记冲突")
                        .build());
            } else {
                firstObjectByKey.put(key, objectKey);
            }
        }

        return CompiledGraphProjection.builder()
                .nodeClaims(new ArrayList<>(nodeClaimsByIdentity.values()))
                .edgeClaims(edgeClaims)
                .issues(issues)
                .compiledCount(compiled)
                .skippedCount(skipped)
                .dryRun(options.isDryRun())
                .build();
    }

    /**
     * 获取 dry-run 差异报告（Markdown 格式）。
     */
    public String generateDiffReport(CompiledGraphProjection projection) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Claim 编译差异报告\n\n");
        sb.append("- 编译成功: ").append(projection.getCompiledCount()).append(" 条\n");
        sb.append("- 跳过: ").append(projection.getSkippedCount()).append(" 条\n");
        sb.append("- 边声明: ").append(projection.getEdgeClaims().size()).append(" 条\n");
        sb.append("- 问题: ").append(projection.getIssues().size()).append(" 个\n\n");

        if (!projection.getIssues().isEmpty()) {
            sb.append("## 编译问题\n\n");
            for (ClaimCompileIssue issue : projection.getIssues()) {
                sb.append("- **").append(issue.getIssueType()).append("**: ")
                        .append(issue.getDescription())
                        .append("\n  建议: ").append(issue.getSuggestedAction()).append("\n");
            }
        }
        return sb.toString();
    }

    private List<KnowledgeClaim> filterClaims(List<KnowledgeClaim> claims, CompileOptions options) {
        if (options == null) {
            options = CompileOptions.builder().build();
        }
        CompileOptions compileOptions = options;
        return claims.stream()
                .filter(c -> compileOptions.getSubjectTypes().isEmpty()
                        || compileOptions.getSubjectTypes().contains(c.getSubjectType()))
                .filter(c -> compileOptions.getPredicates().isEmpty()
                        || compileOptions.getPredicates().contains(c.getPredicate()))
                .filter(c -> c.getConfidence() != null
                        && c.getConfidence().compareTo(compileOptions.getMinConfidence()) >= 0)
                .collect(Collectors.toList());
    }

    private void addNodeClaim(Map<String, GraphNodeClaim> nodeClaimsByIdentity,
                              KnowledgeClaim claim, String nodeType, String nodeKey) {
        if (nodeType == null || nodeType.isBlank() || nodeKey == null || nodeKey.isBlank()) {
            return;
        }
        String identity = nodeType + "|" + nodeKey;
        nodeClaimsByIdentity.computeIfAbsent(identity, ignored -> GraphNodeClaim.builder()
                .projectId(claim.getProjectId())
                .versionId(claim.getVersionId())
                .nodeType(nodeType)
                .nodeKey(nodeKey)
                .nodeName(nodeKey)
                .displayName(nodeKey)
                .sourceType(claim.getSourceType())
                .confidence(claim.getConfidence())
                .status(claim.getStatus())
                .build());
    }

    private GraphEdgeClaim buildEdgeClaim(KnowledgeClaim claim, String edgeType) {
        String edgeKey = claim.getSubjectKey() + "|" + edgeType + "|" + claim.getObjectKey();
        return GraphEdgeClaim.builder()
                .projectId(claim.getProjectId())
                .versionId(claim.getVersionId())
                .edgeType(edgeType)
                .fromNodeKey(claim.getSubjectKey())
                .toNodeKey(claim.getObjectKey())
                .edgeKey(edgeKey)
                .sourceType(claim.getSourceType())
                .confidence(claim.getConfidence())
                .status(claim.getStatus())
                .claimId(claim.getId())
                .build();
    }
}
