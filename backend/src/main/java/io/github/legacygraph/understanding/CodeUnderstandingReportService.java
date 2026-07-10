package io.github.legacygraph.understanding;

import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.understanding.CodeUnderstandingTaskResult;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ToolRunEntity;
import io.github.legacygraph.entity.ToolEvidenceEntity;
import io.github.legacygraph.repository.ToolEvidenceRepository;
import io.github.legacygraph.repository.ToolRunRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 代码理解报告服务 —— 按第 10 节模板生成 Markdown 报告。
 *
 * <p>报告结构：
 * <ol>
 *   <li>任务背景</li>
 *   <li>工具运行状态</li>
 *   <li>架构视图</li>
 *   <li>功能链路</li>
 *   <li>已确认事实</li>
 *   <li>AI 推断和待确认候选</li>
 *   <li>缺口和风险</li>
 *   <li>建议验证动作</li>
 *   <li>证据索引</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeUnderstandingReportService {

    private final ToolRunRepository toolRunRepository;
    private final ToolEvidenceRepository toolEvidenceRepository;
    private final Neo4jGraphDao neo4jGraphDao;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 聚合报告中功能点章节的标题前缀。 */
    static final String FEATURE_SECTION_PREFIX = "## 功能点：";
    /** splitReportBySections 无法识别任何二级标题时使用的占位 key。 */
    static final String WHOLE_SECTION_KEY = "__whole__";
    /** splitReportBySections 首个标题之前内容（报告头部）的占位 key。 */
    static final String HEADER_SECTION_KEY = "__header__";

    /**
     * 生成代码理解 Markdown 报告。
     */
    public String generateMarkdown(String projectId, String taskId,
                                    CodeUnderstandingTaskResult taskResult, String question) {
        StringBuilder sb = new StringBuilder();

        // 1. 任务背景
        appendTaskBackground(sb, projectId, question);

        // 2. 工具运行状态
        List<ToolRunEntity> runs = queryRuns(taskId);
        appendToolStatus(sb, runs);

        // 3. 架构视图（预留）
        appendArchitectureView(sb);

        // 4. 功能链路
        appendFeatureLinks(sb, runs);

        // 5. 已确认事实
        List<ToolEvidenceEntity> confirmedEvidence = queryConfirmedEvidence(runs);
        appendConfirmedFacts(sb, confirmedEvidence);

        // 6. AI 推断和待确认候选
        List<ToolEvidenceEntity> pendingEvidence = queryPendingEvidence(runs);
        appendPendingCandidates(sb, pendingEvidence);

        // 7. 缺口和风险
        appendGapsAndRisks(sb, runs);

        // 8. 建议验证动作
        appendVerificationSuggestions(sb);

        // 9. 证据索引
        appendEvidenceIndex(sb, runs);

        sb.append("\n---\n");
        sb.append("*由 LegacyGraph 代码理解模块自动生成*");
        return sb.toString();
    }

    private void appendTaskBackground(StringBuilder sb, String projectId, String question) {
        sb.append("# 代码理解报告\n\n");
        sb.append("## 1. 任务背景\n\n");
        sb.append(String.format("**项目ID:** %s\n\n", projectId));
        sb.append(String.format("**用户问题:** %s\n\n", question != null ? question : "未指定"));
        sb.append(String.format("**生成时间:** %s\n\n", LocalDateTime.now().format(DATE_FORMATTER)));
    }

    private void appendToolStatus(StringBuilder sb, List<ToolRunEntity> runs) {
        sb.append("## 2. 工具运行状态\n\n");
        sb.append("| 工具名称 | 操作 | 状态 | 耗时(ms) | 索引新鲜度 |\n");
        sb.append("|----------|------|------|----------|----------|\n");
        if (runs.isEmpty()) {
            sb.append("| - | - | 无工具运行记录 | - | - |\n");
        } else {
            for (ToolRunEntity run : runs) {
                sb.append(String.format("| %s | %s | %s | %d | %s |\n",
                        run.getToolName(),
                        run.getOperation(),
                        run.getStatus(),
                        run.getElapsedMs() != null ? run.getElapsedMs() : 0,
                        run.getIndexFreshness() != null ? run.getIndexFreshness() : "N/A"));
            }
        }
        sb.append("\n");
    }

    private void appendArchitectureView(StringBuilder sb) {
        sb.append("## 3. 架构视图\n\n");
        sb.append("> 📝 架构视图由本地图谱和外部工具共同构建，具体内容取决于分析范围。\n\n");
    }

    private void appendFeatureLinks(StringBuilder sb, List<ToolRunEntity> runs) {
        sb.append("## 4. 功能链路\n\n");
        if (runs.isEmpty()) {
            sb.append("> ⚠️ 未进行工具查询，无法生成功能链路分析。\n\n");
            return;
        }
        sb.append("> 📝 功能链路证据来自工具查询结果和本地图谱节点。\n\n");
    }

    private void appendConfirmedFacts(StringBuilder sb, List<ToolEvidenceEntity> evidence) {
        sb.append("## 5. 已确认事实 ✅\n\n");
        if (evidence.isEmpty()) {
            sb.append("> ⚠️ 暂无已确认事实。\n\n");
            return;
        }
        sb.append("| # | 证据类型 | 源文件 | 符号 | 置信度 |\n");
        sb.append("|---|----------|--------|------|--------|\n");
        int i = 1;
        for (ToolEvidenceEntity ev : evidence) {
            sb.append(String.format("| %d | %s | %s | %s | %.0f%% |\n",
                    i++,
                    ev.getEvidenceType(),
                    ev.getSourcePath() != null ? ev.getSourcePath() : "-",
                    ev.getSymbolQn() != null ? ev.getSymbolQn() : "-",
                    ev.getConfidence() != null ? ev.getConfidence() * 100 : 50));
        }
        sb.append("\n");
    }

    private void appendPendingCandidates(StringBuilder sb, List<ToolEvidenceEntity> evidence) {
        sb.append("## 6. AI 推断和待确认候选 ⏳\n\n");
        if (evidence.isEmpty()) {
            sb.append("> ✅ 无不明确的 AI 推断。\n\n");
            return;
        }
        sb.append("> ⚠️ 以下结论标记为 **PENDING_CONFIRM**，需要人工复核。\n\n");
        sb.append("| # | 证据类型 | 源文件 | 内容摘要 |\n");
        sb.append("|---|----------|--------|----------|\n");
        int i = 1;
        for (ToolEvidenceEntity ev : evidence) {
            String excerpt = ev.getExcerpt() != null
                    ? ev.getExcerpt().length() > 80 ? ev.getExcerpt().substring(0, 80) + "..." : ev.getExcerpt()
                    : "-";
            sb.append(String.format("| %d | %s | %s | %s |\n",
                    i++,
                    ev.getEvidenceType(),
                    ev.getSourcePath() != null ? ev.getSourcePath() : "-",
                    excerpt));
        }
        sb.append("\n");
    }

    private void appendGapsAndRisks(StringBuilder sb, List<ToolRunEntity> runs) {
        sb.append("## 7. 缺口和风险\n\n");
        boolean hasFailure = runs.stream().anyMatch(r -> "FAILED".equals(r.getStatus())
                || "UNAVAILABLE".equals(r.getStatus()) || "TIMEOUT".equals(r.getStatus()));
        if (hasFailure) {
            sb.append("### ⚠️ 工具异常\n\n");
            for (ToolRunEntity run : runs) {
                if (!"SUCCESS".equals(run.getStatus())) {
                    sb.append(String.format("- **%s** (%s): %s\n",
                            run.getToolName(), run.getStatus(),
                            run.getErrorExcerpt() != null ? run.getErrorExcerpt() : "无错误详情"));
                }
            }
            sb.append("\n");
        }
        if (runs.isEmpty()) {
            sb.append("> ⚠️ 无工具运行记录，证据不足。\n\n");
        }
    }

    private void appendVerificationSuggestions(StringBuilder sb) {
        sb.append("## 8. 建议验证动作\n\n");
        sb.append("- [ ] 检查关键代码路径与报告描述一致\n");
        sb.append("- [ ] 验证数据库表结构与图谱节点匹配\n");
        sb.append("- [ ] 复核 AI 推断章节中的待确认项\n");
        sb.append("- [ ] 确认工具异常不影响核心结论\n\n");
    }

    private void appendEvidenceIndex(StringBuilder sb, List<ToolRunEntity> runs) {
        sb.append("## 9. 证据索引\n\n");
        if (runs.isEmpty()) {
            sb.append("> 无证据记录。\n\n");
            return;
        }
        sb.append("| 工具运行 ID | 工具名称 | 操作 | 状态 |\n");
        sb.append("|-------------|----------|------|------|\n");
        for (ToolRunEntity run : runs) {
            sb.append(String.format("| %s | %s | %s | %s |\n",
                    run.getId(), run.getToolName(), run.getOperation(), run.getStatus()));
        }
        sb.append("\n");
    }

    private List<ToolRunEntity> queryRuns(String taskId) {
        try {
            // 优先按 projectId 过滤（从 taskId 推断，或使用现有缓存）
            return toolRunRepository.selectList(
                    new LambdaQueryWrapper<ToolRunEntity>()
                            .orderByDesc(ToolRunEntity::getCreatedAt));
        } catch (Exception e) {
            log.warn("查询工具运行记录失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ToolEvidenceEntity> queryConfirmedEvidence(List<ToolRunEntity> runs) {
        if (runs.isEmpty()) return List.of();
        try {
            List<String> runIds = runs.stream().map(ToolRunEntity::getId).collect(Collectors.toList());
            return toolEvidenceRepository.selectList(
                    new LambdaQueryWrapper<ToolEvidenceEntity>()
                            .in(ToolEvidenceEntity::getToolRunId, runIds)
                            .ge(ToolEvidenceEntity::getConfidence, 0.85));
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ToolEvidenceEntity> queryPendingEvidence(List<ToolRunEntity> runs) {
        if (runs.isEmpty()) return List.of();
        try {
            List<String> runIds = runs.stream().map(ToolRunEntity::getId).collect(Collectors.toList());
            return toolEvidenceRepository.selectList(
                    new LambdaQueryWrapper<ToolEvidenceEntity>()
                            .in(ToolEvidenceEntity::getToolRunId, runIds)
                            .and(w -> w.isNull(ToolEvidenceEntity::getConfidence)
                                    .or().lt(ToolEvidenceEntity::getConfidence, 0.85)));
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== 聚合报告（按功能点组织章节） ====================

    /**
     * 生成聚合 Markdown 报告，按功能点（Feature 节点）组织章节。
     *
     * <p>报告结构：
     * <ol>
     *   <li>报告头部（项目/版本/时间）</li>
     *   <li>功能点概览（表格）</li>
     *   <li>每个 Feature 节点一个章节（标题形如 {@code ## 功能点：xxx}）</li>
     * </ol>
     */
    public String generateAggregatedMarkdown(String projectId, String versionId) {
        log.debug("generateAggregatedMarkdown: projectId={}, versionId={}", projectId, versionId);
        StringBuilder sb = new StringBuilder();
        sb.append("# 代码理解聚合报告\n\n");
        sb.append(String.format("**项目ID:** %s\n\n", projectId));
        sb.append(String.format("**版本ID:** %s\n\n", versionId != null ? versionId : "default"));
        sb.append(String.format("**生成时间:** %s\n\n", LocalDateTime.now().format(DATE_FORMATTER)));

        List<GraphNode> features = queryFeatures(projectId, versionId);
        appendFeatureOverview(sb, features);

        for (GraphNode feature : features) {
            sb.append("\n---\n\n");
            appendFeatureSection(sb, feature);
        }

        sb.append("\n---\n");
        sb.append("*由 LegacyGraph 代码理解模块自动生成*");
        return sb.toString();
    }

    /**
     * 章节级增量生成聚合 Markdown 报告。
     *
     * <p>仅重新生成受影响功能点的章节，未受影响章节保留旧报告内容。
     * 调用方（ScanArtifactPublisher）负责读取旧报告文件并作为 {@code existingReport} 传入。
     *
     * @param projectId       项目 ID
     * @param versionId       版本 ID
     * @param affectedNodeIds 受影响节点 ID 集合
     * @return 完整报告 Markdown；旧报告为空时全量生成
     */
    public String generateIncrementalAggregatedMarkdown(String projectId, String versionId,
                                                         Set<String> affectedNodeIds) {
        return generateIncrementalAggregatedMarkdown(projectId, versionId, affectedNodeIds, null);
    }

    /**
     * 章节级增量生成聚合 Markdown 报告（接收旧报告内容）。
     *
     * @param existingReport 旧报告内容；为 null/空时全量生成
     */
    public String generateIncrementalAggregatedMarkdown(String projectId, String versionId,
                                                         Set<String> affectedNodeIds,
                                                         String existingReport) {
        if (existingReport == null || existingReport.isBlank()) {
            log.info("旧报告不存在，全量生成聚合报告: projectId={}, versionId={}", projectId, versionId);
            return generateAggregatedMarkdown(projectId, versionId);
        }
        Set<String> affected = affectedNodeIds == null ? Set.of() : affectedNodeIds;
        if (affected.isEmpty()) {
            log.info("无受影响节点，保留旧报告: projectId={}, versionId={}", projectId, versionId);
            return existingReport;
        }

        Set<String> affectedFeatureNames = extractFeatureNames(affected, neo4jGraphDao);
        log.info("增量生成报告: projectId={}, 受影响功能点={}", projectId, affectedFeatureNames);

        LinkedHashMap<String, String> oldSections = splitReportBySections(existingReport);
        if (oldSections.isEmpty() || oldSections.containsKey(WHOLE_SECTION_KEY)) {
            log.warn("旧报告无法按章节分割，退化为全量生成: projectId={}", projectId);
            return generateAggregatedMarkdown(projectId, versionId);
        }

        List<GraphNode> features = queryFeatures(projectId, versionId);
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : oldSections.entrySet()) {
            String title = entry.getKey();
            String body = entry.getValue();
            if (isAffectedFeatureSection(title, affectedFeatureNames)) {
                GraphNode matched = findFeatureByTitle(features, title);
                if (matched != null) {
                    StringBuilder rebuilt = new StringBuilder();
                    appendFeatureSection(rebuilt, matched);
                    result.append(rebuilt);
                } else {
                    log.warn("受影响功能点在图谱中未找到，保留旧章节: {}", title);
                    result.append(body);
                }
            } else {
                result.append(body);
            }
        }
        return result.toString();
    }

    // ==================== 辅助方法 ====================

    /**
     * 按二级标题（{@code ## }）分割 Markdown 报告为有序章节。
     *
     * @return LinkedHashMap<title, sectionContent>，保持原文顺序；首个标题之前的内容
     *         使用 {@link #HEADER_SECTION_KEY} 作为 key；无法识别任何标题时整体作为一段，
     *         key 为 {@link #WHOLE_SECTION_KEY}。title 为完整标题行（含 {@code ##} 前缀）。
     */
    LinkedHashMap<String, String> splitReportBySections(String markdown) {
        LinkedHashMap<String, String> sections = new LinkedHashMap<>();
        if (markdown == null || markdown.isBlank()) {
            return sections;
        }
        Pattern headingPattern = Pattern.compile("^##\\s+.+$", Pattern.MULTILINE);
        Matcher matcher = headingPattern.matcher(markdown);
        List<int[]> headingPositions = new ArrayList<>();
        while (matcher.find()) {
            headingPositions.add(new int[]{matcher.start(), matcher.end()});
        }
        if (headingPositions.isEmpty()) {
            sections.put(WHOLE_SECTION_KEY, markdown);
            return sections;
        }
        int firstHeadingStart = headingPositions.get(0)[0];
        if (firstHeadingStart > 0) {
            sections.put(HEADER_SECTION_KEY, markdown.substring(0, firstHeadingStart));
        }
        for (int i = 0; i < headingPositions.size(); i++) {
            int titleStart = headingPositions.get(i)[0];
            int titleEnd = headingPositions.get(i)[1];
            String titleLine = markdown.substring(titleStart, titleEnd).trim();
            int sectionEnd = (i + 1 < headingPositions.size())
                    ? headingPositions.get(i + 1)[0]
                    : markdown.length();
            sections.put(titleLine, markdown.substring(titleStart, sectionEnd));
        }
        return sections;
    }

    /**
     * 从受影响节点 ID 集合中提取 Feature 节点名称。
     *
     * @param affectedNodeIds 受影响节点 ID 集合
     * @param dao             图谱访问对象
     * @return 受影响 Feature 节点的展示名集合（保持插入顺序）；查询失败返回空集合
     */
    Set<String> extractFeatureNames(Set<String> affectedNodeIds, Neo4jGraphDao dao) {
        if (affectedNodeIds == null || affectedNodeIds.isEmpty()) {
            return Set.of();
        }
        try {
            List<GraphNode> nodes = dao.findNodesByIds(new ArrayList<>(affectedNodeIds));
            return nodes.stream()
                    .filter(n -> NodeType.Feature.name().equals(n.getNodeType()))
                    .map(this::resolveFeatureName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            log.warn("提取受影响 Feature 名称失败: {}", e.getMessage());
            return Set.of();
        }
    }

    private List<GraphNode> queryFeatures(String projectId, String versionId) {
        try {
            return neo4jGraphDao.queryNodes(projectId, versionId, NodeType.Feature.name(),
                    null, null, null, null, 500);
        } catch (Exception e) {
            log.warn("查询 Feature 节点失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void appendFeatureOverview(StringBuilder sb, List<GraphNode> features) {
        sb.append("## 功能点概览\n\n");
        if (features.isEmpty()) {
            sb.append("> ⚠️ 未找到功能点（Feature）节点。\n\n");
            return;
        }
        sb.append("| # | 功能点 | 节点Key | 置信度 |\n");
        sb.append("|---|--------|---------|--------|\n");
        int i = 1;
        for (GraphNode f : features) {
            sb.append(String.format("| %d | %s | %s | %s |\n",
                    i++, resolveFeatureName(f), f.getNodeKey(),
                    f.getConfidence() != null ? f.getConfidence() : "N/A"));
        }
        sb.append("\n");
    }

    private void appendFeatureSection(StringBuilder sb, GraphNode feature) {
        String featureName = resolveFeatureName(feature);
        sb.append(FEATURE_SECTION_PREFIX).append(featureName).append("\n\n");
        sb.append(String.format("- **节点ID:** %s\n", feature.getId()));
        sb.append(String.format("- **节点Key:** %s\n", feature.getNodeKey()));
        if (feature.getDescription() != null && !feature.getDescription().isBlank()) {
            sb.append(String.format("- **描述:** %s\n", feature.getDescription()));
        }
        sb.append(String.format("- **置信度:** %s\n",
                feature.getConfidence() != null ? feature.getConfidence() : "N/A"));
        sb.append("\n");
        sb.append("> 📝 该功能点的链路、证据和推断来自本地图谱节点及关联工具运行结果。\n\n");
    }

    private String resolveFeatureName(GraphNode feature) {
        if (feature.getDisplayName() != null && !feature.getDisplayName().isBlank()) {
            return feature.getDisplayName();
        }
        return feature.getNodeName() != null ? feature.getNodeName() : feature.getNodeKey();
    }

    private boolean isAffectedFeatureSection(String title, Set<String> affectedFeatureNames) {
        if (title == null || !title.startsWith(FEATURE_SECTION_PREFIX)) {
            return false;
        }
        String name = title.substring(FEATURE_SECTION_PREFIX.length()).trim();
        return affectedFeatureNames.contains(name);
    }

    private GraphNode findFeatureByTitle(List<GraphNode> features, String title) {
        if (title == null || !title.startsWith(FEATURE_SECTION_PREFIX)) {
            return null;
        }
        String name = title.substring(FEATURE_SECTION_PREFIX.length()).trim();
        return features.stream()
                .filter(f -> resolveFeatureName(f).equals(name))
                .findFirst()
                .orElse(null);
    }
}
