package io.github.legacygraph.service.qa;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.EvidenceItem;
import io.github.legacygraph.dto.qa.AccessContext;
import io.github.legacygraph.dto.qa.VerificationResult;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.GraphRelease;
import io.github.legacygraph.repository.GraphReleaseRepository;
import io.github.legacygraph.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 证据验证器 — 校验 LLM 答案中的关键引用是否由可靠证据支撑。
 *
 * <p>验证流程：</p>
 * <ol>
 *   <li><b>存在性校验</b>：每个 evidenceId 在图谱中存在</li>
 *   <li><b>归属校验</b>：证据属于当前 project + graphRelease</li>
 *   <li><b>ACL 校验</b>：用户 principals 能访问该证据</li>
 *   <li><b>sourceLocation 校验</b>：证据有 sourceFile（非空）</li>
 *   <li><b>答案声明匹配</b>：答案中的关键引用（实体名、文件路径、方法名）与证据匹配</li>
 * </ol>
 *
 * <p>产出 {@link VerificationResult}，含 evidenceCoverage 和 evidenceReliability 两项核心指标。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceVerifier {

    private final Neo4jGraphDao neo4jGraphDao;
    private final GraphReleaseRepository graphReleaseRepository;

    /** 答案声明提取：文件路径（/path/to/File.java）、类名/方法名（CamelCase 标识符） */
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
        "[\\w/\\\\]+\\.(java|js|ts|vue|xml|sql|py|go|kt|yml|yaml|properties|md)");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
        "\\b[A-Z][a-zA-Z0-9_]{2,}\\b");

    /** 证据声明匹配的最小证据数：至少匹配 1 条才视为有支撑 */
    private static final int MIN_MATCHED_EVIDENCE = 1;

    /**
     * 验证答案与证据的匹配度。
     *
     * @param answer         LLM 生成的答案文本
     * @param evidences      检索到的证据列表
     * @param projectId      项目 ID
     * @param graphReleaseId 图谱发布 ID（可为 null，表示不校验版本归属）
     * @param accessContext   访问上下文（principals + team）
     * @return 验证结果
     */
    public VerificationResult verify(String answer,
                                     List<EvidenceItem> evidences,
                                     String projectId,
                                     String graphReleaseId,
                                     AccessContext accessContext) {
        if (answer == null || answer.isBlank()) {
            return new VerificationResult(false, 0.0, 0.0,
                List.of(), List.of(), List.of(), List.of("答案为空"));
        }
        if (evidences == null || evidences.isEmpty()) {
            return new VerificationResult(false, 0.0, 0.0,
                List.of(), List.of(), List.of(), List.of("无证据"));
        }

        List<String> violations = new ArrayList<>();
        List<EvidenceItem> verifiedEvidences = new ArrayList<>();

        // 预解析 graphReleaseId → scanVersionId，用于校验图谱节点版本归属
        String releaseScanVersionId = null;
        if (graphReleaseId != null && !graphReleaseId.isBlank()) {
            try {
                GraphRelease release = graphReleaseRepository.selectById(graphReleaseId);
                if (release != null && release.getScanVersionId() != null) {
                    releaseScanVersionId = IdUtil.normalizeId(release.getScanVersionId());
                }
            } catch (Exception e) {
                log.warn("Failed to resolve GraphRelease for id {}: {}", graphReleaseId, e.getMessage());
            }
        }

        // 1~4. 逐条校验证据
        Set<String> verifiedRefSet = new LinkedHashSet<>();
        Set<String> verifiedTitles = new LinkedHashSet<>();
        Set<String> verifiedSourceFiles = new LinkedHashSet<>();

        for (EvidenceItem ev : evidences) {
            if (ev == null) continue;
            String violation = checkEvidence(ev, projectId, accessContext, releaseScanVersionId);
            if (violation != null) {
                violations.add(violation);
                continue;
            }
            verifiedEvidences.add(ev);
            if (ev.getRef() != null) verifiedRefSet.add(ev.getRef());
            if (ev.getTitle() != null) verifiedTitles.add(ev.getTitle());
            if (ev.getSourceFile() != null) verifiedSourceFiles.add(ev.getSourceFile());
        }

        // 5. 答案声明匹配：提取答案中的关键引用，检查是否有证据支撑
        List<String> allClaims = extractClaims(answer);
        List<String> matchedClaims = new ArrayList<>();
        List<String> unmatchedClaims = new ArrayList<>();

        for (String claim : allClaims) {
            if (isClaimSupported(claim, verifiedTitles, verifiedSourceFiles)) {
                matchedClaims.add(claim);
            } else {
                unmatchedClaims.add(claim);
            }
        }

        // evidenceCoverage: 答案声明中有证据支撑的比例
        double coverage = allClaims.isEmpty() ? 0.0
            : (double) matchedClaims.size() / allClaims.size();

        // evidenceReliability: 证据可靠度 = 通过校验的证据比例 × sourceLocation 完整率
        double reliability = computeReliability(evidences, verifiedEvidences);

        boolean verified = coverage >= VerificationResult.LOW_COVERAGE_THRESHOLD
            && verifiedEvidences.size() >= MIN_MATCHED_EVIDENCE;

        log.debug("Evidence verification: coverage={}, reliability={}, matched={}, unmatched={}, violations={}",
            String.format("%.2f", coverage), String.format("%.2f", reliability),
            matchedClaims.size(), unmatchedClaims.size(), violations.size());

        return new VerificationResult(verified, coverage, reliability,
            matchedClaims, unmatchedClaims, verifiedEvidences, violations);
    }

    /**
     * 校验单条证据 — 返回 null 表示通过，非 null 为违规描述。
     *
     * @param releaseScanVersionId 预解析的 GraphRelease.scanVersionId（normalizeId 后），
     *                             null 表示不校验 release 归属
     */
    private String checkEvidence(EvidenceItem ev, String projectId,
                                  AccessContext accessContext, String releaseScanVersionId) {
        String ref = ev.getRef();

        // 1. 存在性校验：GRAPH_NODE 类型查图谱
        if ("GRAPH_NODE".equals(ev.getSourceKind()) && ref != null && !ref.isBlank()) {
            try {
                List<GraphNode> nodes = neo4jGraphDao.findNodesByIds(List.of(ref));
                if (nodes == null || nodes.isEmpty()) {
                    return "证据不存在: " + ref;
                }
                // 2. 归属校验：检查 projectId
                GraphNode node = nodes.get(0);
                if (node.getProjectId() != null && !node.getProjectId().equals(projectId)) {
                    return "证据归属不符: 证据属于 " + node.getProjectId() + "，当前项目 " + projectId;
                }
                // 2.1 Release 归属校验：releaseScanVersionId 非空时校验节点属于该 release
                if (releaseScanVersionId != null) {
                    String nodeVersionId = node.getVersionId();
                    if (nodeVersionId == null || nodeVersionId.isBlank()) {
                        return "证据缺少 versionId，无法校验 release 归属: " + ref;
                    }
                    if (!releaseScanVersionId.equals(IdUtil.normalizeId(nodeVersionId))) {
                        return "证据不属于当前 release: 节点 versionId=" + nodeVersionId
                            + "，release scanVersionId=" + releaseScanVersionId;
                    }
                }
            } catch (Exception e) {
                log.warn("存在性校验失败 ref={}: {}", ref, e.getMessage());
                return "存在性校验失败: " + ref;
            }
        }

        // 3. ACL 校验：检查用户 principals 能否访问
        if (!checkAcl(ev, accessContext)) {
            return "ACL 不可访问: " + ref;
        }

        // 4. sourceLocation 校验
        if (ev.getSourceFile() == null || ev.getSourceFile().isBlank()) {
            // sourceLocation 非空是软校验：缺失不直接判失败，但降低可靠度
            // 此处不返回违规，由 reliability 计算时体现
        }

        return null;
    }

    /**
     * ACL 校验 — 证据无 ACL 限制时可通过；有 ACL 时检查 principals 是否有交集。
     */
    private boolean checkAcl(EvidenceItem ev, AccessContext accessContext) {
        List<String> evidenceAcl = ev.getAclPrincipals();
        if (evidenceAcl == null || evidenceAcl.isEmpty()) {
            // 证据无 ACL 限制，所有人可访问
            return true;
        }
        if (accessContext == null || accessContext.principals().isEmpty()) {
            // PUBLIC 上下文但证据有 ACL 限制，拒绝访问
            return false;
        }
        // 检查用户 principals 与证据 ACL 是否有交集
        return accessContext.principals().stream().anyMatch(evidenceAcl::contains);
    }

    /**
     * 提取答案中的关键声明：文件路径和显著标识符（类名/方法名等）。
     */
    private List<String> extractClaims(String answer) {
        Set<String> claims = new LinkedHashSet<>();

        // 文件路径
        Matcher fileMatcher = FILE_PATH_PATTERN.matcher(answer);
        while (fileMatcher.find()) {
            claims.add(fileMatcher.group());
        }

        // CamelCase 标识符（过滤常见无意义词）
        Matcher idMatcher = IDENTIFIER_PATTERN.matcher(answer);
        while (idMatcher.find()) {
            String id = idMatcher.group();
            if (!isStopword(id)) {
                claims.add(id);
            }
        }

        return new ArrayList<>(claims);
    }

    /** 停用词：不作为有效声明的常见词 */
    private static final Set<String> STOPWORDS = Set.of(
        "The", "This", "That", "These", "Those", "What", "When", "Where",
        "Why", "How", "Which", "Who", "There", "Here", "Service", "Controller",
        "Mapper", "Table", "Entity", "Result", "List", "Map", "Set",
        "JSON", "HTTP", "POST", "GET", "PUT", "DELETE", "API"
    );

    private boolean isStopword(String id) {
        return STOPWORDS.contains(id);
    }

    /**
     * 检查声明是否有证据支撑 — 匹配证据的 title 或 sourceFile。
     */
    private boolean isClaimSupported(String claim,
                                     Set<String> verifiedTitles,
                                     Set<String> verifiedSourceFiles) {
        // 文件路径直接匹配 sourceFile
        for (String sf : verifiedSourceFiles) {
            if (sf != null && sf.contains(claim)) {
                return true;
            }
        }
        // 标识符匹配 title（忽略大小写）
        String claimLower = claim.toLowerCase();
        for (String title : verifiedTitles) {
            if (title != null && title.toLowerCase().contains(claimLower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算证据可靠度 = 通过校验比例 × 0.6 + sourceLocation 完整率 × 0.4。
     */
    private double computeReliability(List<EvidenceItem> allEvidences,
                                       List<EvidenceItem> verifiedEvidences) {
        double passRate = allEvidences.isEmpty() ? 0.0
            : (double) verifiedEvidences.size() / allEvidences.size();

        long withSource = verifiedEvidences.stream()
            .filter(e -> e.getSourceFile() != null && !e.getSourceFile().isBlank())
            .count();
        double sourceRate = verifiedEvidences.isEmpty() ? 0.0
            : (double) withSource / verifiedEvidences.size();

        return passRate * 0.6 + sourceRate * 0.4;
    }
}
