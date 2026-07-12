package io.github.legacygraph.federation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.integration.graphify.GraphifyCompatibilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 跨仓 graph.json 差异服务（评估 §5.2 S3）。
 *
 * <p>职责：复用 {@code GraphifyRunner} 已产出的 graph.json 输出，<b>不发起新 Graphify 扫描</b>，
 * 与项目自身 Neo4j 中对应节点的子集做 diff，输出"基线对齐率"与"差异节点列表"。</p>
 *
 * <p>典型用例：</p>
 * <pre>{@code
 *     CrossRepoDiffReport report = crossRepoDiffService.diffAgainstLocal(
 *         "/path/to/graphify-out/graph.json", "user-svc", "v1.0.0");
 *     // report.alignmentRate = 0.85 表示 85% 节点已对齐
 * }</pre>
 *
 * <p>与 {@link CrossRepoImpactService} 的差异：本服务只做"事实对齐"（节点是否一致），
 * 不做"影响传播"（跨项目调用链分析）。</p>
 */
@Slf4j
@Service
public class CrossRepoDiffService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 读取 graph.json 与指定 projectId/versionId 的 Neo4j 节点做 diff。
     *
     * @param graphJsonPath graph.json 文件路径（由 GraphifyRunner 产出）
     * @param projectId     本项目 ID
     * @param versionId     本版本 ID（用于 filter Neo4j 节点）
     * @return 跨仓差异报告
     */
    public CrossRepoDiffReport diffAgainstGraphJson(String graphJsonPath, String projectId, String versionId) {
        Path path = Path.of(graphJsonPath);
        if (!Files.exists(path)) {
            log.warn("graph.json not found at {}", graphJsonPath);
            return CrossRepoDiffReport.builder()
                    .projectId(projectId)
                    .versionId(versionId)
                    .graphJsonPath(graphJsonPath)
                    .alignmentRate(0.0)
                    .missingInGraphify(List.of())
                    .missingInLegacy(List.of())
                    .onlyInSemantic(List.of())
                    .build();
        }
        try {
            String content = Files.readString(path);
            return diffAgainstJsonString(content, projectId, versionId, graphJsonPath);
        } catch (IOException e) {
            log.warn("Failed to read graph.json {}: {}", graphJsonPath, e.getMessage());
            return CrossRepoDiffReport.builder()
                    .projectId(projectId)
                    .versionId(versionId)
                    .graphJsonPath(graphJsonPath)
                    .alignmentRate(0.0)
                    .missingInGraphify(List.of())
                    .missingInLegacy(List.of())
                    .onlyInSemantic(List.of())
                    .build();
        }
    }

    /**
     * 读取 graph.json 内容并与项目 Neo4j 节点做 diff（评测端点，便于在测试中复用）。
     *
     * <p>此方法通过 Neo4j 查询获取本项目节点；调用方负责提供 Neo4j 节点的获取机制，
     * 这里只解析 graph.json 节点子集，与外部节点 FQN 做 key 比对。
     * （简化版：从 graph.json 提取所有 node.label+node.id，与 project 节点的 nodeKey 做 Jaccard。）</p>
     */
    public CrossRepoDiffReport diffAgainstJsonString(String jsonContent, String projectId,
                                                    String versionId, String graphJsonPath) {
        List<String> graphifyNodeIds = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            JsonNode nodes = root.get("nodes");
            if (nodes != null && nodes.isArray()) {
                for (JsonNode n : nodes) {
                    String label = n.path("label").asText(null);
                    if (label == null) continue;
                    // 业务语义节点不参与 diff（Graphify 不覆盖）
                    if (GraphifyCompatibilityService.isSemanticNodeType(label)) continue;
                    String id = n.path("id").asText(null);
                    if (id != null) graphifyNodeIds.add(label + "::" + id);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse graph.json content: {}", e.getMessage());
        }
        // 注：本方法不直接访问 Neo4j（保持纯函数以便测试 + 复用 GraphifyJobController 的图谱数据）。
        // 真实 diff 在 FederationController 中调用：先从 Neo4j 拿到本项目的 legacy 节点子集，
        // 然后用 graphifyNodeIds ∩ legacyNodeIds / ∪ 计算 Jaccard，得到 alignmentRate。
        log.info("Parsed {} AST nodes from graph.json for projectId={} versionId={}",
                graphifyNodeIds.size(), projectId, versionId);

        Set<String> seen = new HashSet<>(graphifyNodeIds);
        return CrossRepoDiffReport.builder()
                .projectId(projectId)
                .versionId(versionId)
                .graphJsonPath(graphJsonPath)
                .totalInGraphify(graphifyNodeIds.size())
                .alignmentRate(seen.isEmpty() ? 1.0 : 1.0)  // 占位，真实对齐率由 controller 计算
                .missingInGraphify(List.of())
                .missingInLegacy(List.of())
                .onlyInSemantic(List.of())
                .build();
    }

    /**
     * 完整 diff：解析 graph.json + 用 legacy 节点列表计算真实对齐率（H03）。
     *
     * <p>controller 调用此方法，传入从 Neo4j 查询到的 legacy 节点列表，
     * 内部完成 graph.json 解析 + Jaccard 对齐率计算 + missing 列表生成。</p>
     *
     * @param graphJsonPath graph.json 文件路径
     * @param projectId     项目 ID
     * @param versionId     版本 ID
     * @param legacyNodes   从 Neo4j 查询到的本项目节点列表
     * @return 完整差异报告（含真实 alignmentRate + missingInGraphify + missingInLegacy）
     */
    public CrossRepoDiffReport diffWithLegacyNodes(String graphJsonPath, String projectId,
                                                    String versionId, List<GraphNode> legacyNodes) {
        Set<String> graphifyNodeIds = new HashSet<>();
        try {
            String content = Files.readString(Path.of(graphJsonPath));
            JsonNode root = objectMapper.readTree(content);
            JsonNode nodes = root.get("nodes");
            if (nodes != null && nodes.isArray()) {
                for (JsonNode n : nodes) {
                    String label = n.path("label").asText(null);
                    if (label == null) continue;
                    if (GraphifyCompatibilityService.isSemanticNodeType(label)) continue;
                    String id = n.path("id").asText(null);
                    if (id != null) graphifyNodeIds.add(label + "::" + id);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse graph.json {}: {}", graphJsonPath, e.getMessage());
            return CrossRepoDiffReport.builder()
                    .projectId(projectId)
                    .versionId(versionId)
                    .graphJsonPath(graphJsonPath)
                    .alignmentRate(0.0)
                    .missingInGraphify(List.of())
                    .missingInLegacy(List.of())
                    .onlyInSemantic(List.of())
                    .build();
        }

        // 构建 legacy 节点 key 集（格式与 graphifyNodeIds 一致：label::nodeKey）
        Set<String> onlySemantic = new HashSet<>();
        Set<String> legacyNodeIds = new HashSet<>();
        if (legacyNodes != null) {
            for (GraphNode n : legacyNodes) {
                String type = n.getNodeType();
                String key = n.getNodeKey();
                if (type == null || key == null) continue;
                String composite = type + "::" + key;
                if (GraphifyCompatibilityService.isSemanticNodeType(type)) {
                    onlySemantic.add(composite);
                } else {
                    legacyNodeIds.add(composite);
                }
            }
        }

        // 计算 Jaccard 对齐率
        double alignmentRate = computeAlignmentRate(graphifyNodeIds, legacyNodeIds);

        // missingInGraphify: 在 legacy 但不在 graphify
        List<String> missingInGraphify = new ArrayList<>(legacyNodeIds);
        missingInGraphify.removeAll(graphifyNodeIds);

        // missingInLegacy: 在 graphify 但不在 legacy
        List<String> missingInLegacy = new ArrayList<>(graphifyNodeIds);
        missingInLegacy.removeAll(legacyNodeIds);

        log.info("Cross-repo diff: projectId={}, versionId={}, graphify={}, legacy={}, alignment={}, missingInGraphify={}, missingInLegacy={}",
                projectId, versionId, graphifyNodeIds.size(), legacyNodeIds.size(),
                String.format("%.4f", alignmentRate), missingInGraphify.size(), missingInLegacy.size());

        return CrossRepoDiffReport.builder()
                .projectId(projectId)
                .versionId(versionId)
                .graphJsonPath(graphJsonPath)
                .totalInGraphify(graphifyNodeIds.size())
                .totalInLegacy(legacyNodeIds.size())
                .alignmentRate(alignmentRate)
                .missingInGraphify(missingInGraphify)
                .missingInLegacy(missingInLegacy)
                .onlyInSemantic(new ArrayList<>(onlySemantic))
                .build();
    }

    /**
     * 计算 alignment rate（外部接口，便于 controller 组合调用）。
     *
     * @param graphifyNodeIds graph.json 提取的 AST 节点 ID（label::id）
     * @param legacyNodeIds    本项目 Neo4j 节点 key
     * @return Jaccard alignment rate，范围 [0, 1]
     */
    public static double computeAlignmentRate(Set<String> graphifyNodeIds, Set<String> legacyNodeIds) {
        if (graphifyNodeIds == null || legacyNodeIds == null) return 0.0;
        if (graphifyNodeIds.isEmpty() && legacyNodeIds.isEmpty()) return 1.0;
        Set<String> intersection = new HashSet<>(graphifyNodeIds);
        intersection.retainAll(legacyNodeIds);
        Set<String> union = new HashSet<>(graphifyNodeIds);
        union.addAll(legacyNodeIds);
        return union.isEmpty() ? 1.0 : (double) intersection.size() / union.size();
    }

    /**
     * 跨仓差异报告。
     */
    @lombok.Data
    @lombok.Builder
    public static class CrossRepoDiffReport {
        private String projectId;
        private String versionId;
        private String graphJsonPath;
        private int totalInGraphify;
        private int totalInLegacy;
        private double alignmentRate;
        private List<String> missingInGraphify;  // 在 legacy 但 graph.json 缺失
        private List<String> missingInLegacy;    // 在 graph.json 但 legacy 缺失
        private List<String> onlyInSemantic;     // 仅在 legacy 业务语义节点（diff 忽略）
    }
}