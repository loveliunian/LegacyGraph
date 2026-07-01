package io.github.legacygraph.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.builder.FeatureSliceBuilder;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.FeatureSlice;
import io.github.legacygraph.entity.*;
import io.github.legacygraph.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 变更/切片级 Markdown 报告装配器（增强版1 + 增强版2）。
 * <p>
 * 直接消费 {@link FeatureSliceBuilder} 的切片投影与 ChangeTask 管道实体，
 * 组装“可读、可审计、可回放”的结构化 Markdown（见 doc §FeatureSlice Markdown 生成细节）。
 * 只负责生成 Markdown 字符串；PDF/HTML 转换复用 {@link ReportExportService} 的既有管线。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeReportService {

    private final FeatureSliceBuilder featureSliceBuilder;
    private final Neo4jGraphDao neo4jGraphDao;
    private final NodeEvidenceRepository nodeEvidenceRepository;
    private final EvidenceRepository evidenceRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final ChangeTaskRepository changeTaskRepository;
    private final PatchFileRepository patchFileRepository;
    private final ValidationGateRepository validationGateRepository;
    private final PrTaskRepository prTaskRepository;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ==================== FeatureSlice Markdown ====================

    /**
     * 生成单个功能切片的 Markdown 说明书。
     * sliceId 即 Feature 节点 ID（与 /graph/feature-slices/{sliceId} 一致）。
     */
    public String generateFeatureSliceMarkdown(String projectId, String sliceId) {
        FeatureSlice slice = featureSliceBuilder.buildSliceById(projectId, sliceId);
        StringBuilder sb = new StringBuilder();

        String featureName = slice.getFeatureName() != null ? slice.getFeatureName() : slice.getName();
        sb.append("# 功能说明书：").append(nullToDash(featureName)).append("\n\n");

        // ---- 概览 ----
        sb.append("## 概览\n\n");
        sb.append("| 项 | 值 |\n|------|------|\n");
        sb.append(String.format("| 功能键 | %s |\n", nullToDash(slice.getSliceId())));
        sb.append(String.format("| 状态 | %s |\n", nullToDash(slice.getStatus())));
        sb.append(String.format("| 置信度 | %s |\n", slice.getConfidence() != null ? slice.getConfidence().toPlainString() : "-"));
        sb.append(String.format("| 覆盖状态 | %s |\n", nullToDash(slice.getCoverageStatus())));
        sb.append(String.format("| 风险等级 | %s |\n", nullToDash(slice.getRiskLevel())));
        sb.append(String.format("| 入口页面 | %s |\n", nullToDash(slice.getEntryPage())));
        sb.append(String.format("| 证据来源 | %s |\n", String.join(", ", orEmpty(slice.getEvidenceSources()))));
        sb.append("\n");

        // ---- 关联节点 ----
        sb.append("## 关联节点\n\n");
        appendNodeGroup(sb, "页面", slice.getPageIds());
        appendNodeGroup(sb, "接口", slice.getApiIds());
        appendNodeGroup(sb, "方法", slice.getMethodIds());
        appendNodeGroup(sb, "SQL", slice.getSqlIds());
        appendNodeGroup(sb, "表", slice.getTableIds());
        appendNodeGroup(sb, "权限", slice.getPermissionIds());
        sb.append("\n");

        // ---- 关系链 ----
        sb.append("## 关系链\n\n");
        List<String> chain = new ArrayList<>();
        if (slice.getEntryPage() != null) chain.add("页面 " + slice.getEntryPage());
        if (!orEmpty(slice.getApiIds()).isEmpty()) chain.add("接口 " + firstNodeName(slice.getApiIds()));
        if (!orEmpty(slice.getMethodIds()).isEmpty()) chain.add("方法 " + firstNodeName(slice.getMethodIds()));
        if (!orEmpty(slice.getSqlIds()).isEmpty()) chain.add("SQL " + firstNodeName(slice.getSqlIds()));
        if (!orEmpty(slice.getTableIds()).isEmpty()) chain.add("表 " + firstNodeName(slice.getTableIds()));
        if (chain.isEmpty()) {
            sb.append("_暂无可展开的关系链_\n\n");
        } else {
            sb.append(String.join("  →  ", chain)).append("\n\n");
        }

        // ---- 证据（按类型分组） ----
        sb.append("## 证据\n\n");
        List<String> allNodeIds = new ArrayList<>();
        allNodeIds.addAll(orEmpty(slice.getApiIds()));
        allNodeIds.addAll(orEmpty(slice.getMethodIds()));
        allNodeIds.addAll(orEmpty(slice.getSqlIds()));
        allNodeIds.addAll(orEmpty(slice.getPageIds()));
        Map<String, List<String>> evidenceByType = collectEvidence(allNodeIds);
        if (evidenceByType.isEmpty()) {
            sb.append("_未采集到证据_\n\n");
        } else {
            evidenceByType.forEach((type, items) -> {
                sb.append("- **").append(type.toUpperCase()).append("**\n");
                items.stream().limit(20).forEach(item -> sb.append("  - ").append(item).append("\n"));
            });
            sb.append("\n");
        }

        // ---- 测试结果 ----
        sb.append("## 测试结果\n\n");
        appendTestResults(sb, slice.getTestCaseIds());

        // ---- 风险与建议 ----
        if (!"COVERED".equals(slice.getCoverageStatus()) || !"LOW".equals(slice.getRiskLevel())) {
            sb.append("## 风险与建议\n\n");
            if (!"COVERED".equals(slice.getCoverageStatus())) {
                sb.append("- 覆盖状态为 ").append(nullToDash(slice.getCoverageStatus()))
                        .append("，存在链路缺口，建议补充抽取或人工确认证据。\n");
            }
            if ("HIGH".equals(slice.getRiskLevel())) {
                sb.append("- 风险等级为 HIGH，若后续修复该功能，必须同时回归状态流转与权限校验。\n");
            }
            sb.append("\n");
        }

        sb.append("---\n*由 LegacyGraph 自动生成*");
        return sb.toString();
    }

    // ==================== ChangeTask Markdown ====================

    /**
     * 生成单个变更任务的 Markdown 说明书。
     */
    public String generateChangeTaskMarkdown(String projectId, String taskId) {
        ChangeTask task = changeTaskRepository.selectById(taskId);
        StringBuilder sb = new StringBuilder();

        if (task == null) {
            sb.append("# 变更任务：未找到\n\n");
            sb.append("任务 ID `").append(nullToDash(taskId)).append("` 不存在或已删除。\n");
            return sb.toString();
        }

        sb.append("# 变更任务：").append(nullToDash(task.getTitle())).append("\n\n");

        // ---- 概览 ----
        sb.append("## 概览\n\n");
        sb.append("| 项 | 值 |\n|------|------|\n");
        sb.append(String.format("| 任务 ID | %s |\n", nullToDash(task.getId())));
        sb.append(String.format("| 类型 | %s |\n", nullToDash(task.getTaskType())));
        sb.append(String.format("| 状态 | %s |\n", nullToDash(task.getStatus())));
        sb.append(String.format("| 风险等级 | %s |\n", nullToDash(task.getRiskLevel())));
        sb.append(String.format("| 创建时间 | %s |\n",
                task.getCreatedAt() != null ? task.getCreatedAt().format(DATE_FORMATTER) : "-"));
        sb.append("\n");

        // ---- 输入问题 ----
        if (task.getInputIssue() != null && !task.getInputIssue().isBlank()) {
            sb.append("## 输入问题\n\n```json\n").append(task.getInputIssue()).append("\n```\n\n");
        }

        // ---- 影响子图 ----
        if (task.getImpactedSubgraph() != null && !task.getImpactedSubgraph().isBlank()) {
            sb.append("## 影响子图\n\n```json\n").append(task.getImpactedSubgraph()).append("\n```\n\n");
        }

        // ---- 补丁文件 ----
        sb.append("## 补丁文件\n\n");
        List<PatchFile> patches = patchFileRepository.lambdaQuery()
                .eq(PatchFile::getChangeTaskId, taskId).list();
        if (patches.isEmpty()) {
            sb.append("_暂无补丁_\n\n");
        } else {
            sb.append("| 文件 | 变更类型 | 生成者 | 状态 |\n|------|------|------|------|\n");
            for (PatchFile p : patches) {
                sb.append(String.format("| %s | %s | %s | %s |\n",
                        nullToDash(p.getFilePath()), nullToDash(p.getChangeType()),
                        nullToDash(p.getGeneratedBy()), nullToDash(p.getStatus())));
            }
            sb.append("\n");
        }

        // ---- 验证门禁 ----
        sb.append("## 验证门禁\n\n");
        List<ValidationGate> gates = validationGateRepository.lambdaQuery()
                .eq(ValidationGate::getChangeTaskId, taskId).list();
        if (gates.isEmpty()) {
            sb.append("_暂无门禁记录_\n\n");
        } else {
            sb.append("| 门禁 | 结果 | 报告 |\n|------|------|------|\n");
            for (ValidationGate g : gates) {
                sb.append(String.format("| %s | %s | %s |\n",
                        nullToDash(g.getGateType()), nullToDash(g.getResult()), nullToDash(g.getReportUri())));
            }
            sb.append("\n");
        }

        // ---- PR ----
        sb.append("## PR\n\n");
        List<PrTask> prs = prTaskRepository.lambdaQuery()
                .eq(PrTask::getChangeTaskId, taskId).list();
        if (prs.isEmpty()) {
            sb.append("_尚未创建 PR_\n\n");
        } else {
            for (PrTask pr : prs) {
                sb.append(String.format("- 分支 `%s` — 状态 %s%s\n",
                        nullToDash(pr.getBranchName()), nullToDash(pr.getPrStatus()),
                        pr.getPrUrl() != null ? " — " + pr.getPrUrl() : ""));
                appendReviewerPolicy(sb, pr.getReviewerPolicy());
                appendRollbackPlan(sb, pr.getRollbackPlan());
            }
            sb.append("\n");
        }

        sb.append("---\n*由 LegacyGraph 自动生成*");
        return sb.toString();
    }

    /** 渲染 reviewer 策略（PrTask.reviewerPolicy JSON）。 */
    private void appendReviewerPolicy(StringBuilder sb, String reviewerPolicyJson) {
        if (reviewerPolicyJson == null || reviewerPolicyJson.isBlank()) return;
        try {
            Map<String, Object> policy = objectMapper.readValue(
                    reviewerPolicyJson, new TypeReference<Map<String, Object>>() {});
            sb.append("  - 审核策略：");
            List<String> parts = new ArrayList<>();
            if (policy.get("minReviewers") != null) {
                parts.add("至少 " + policy.get("minReviewers") + " 人");
            }
            if (Boolean.TRUE.equals(policy.get("dbaRequired"))) {
                parts.add("须 DBA 审核");
            }
            sb.append(parts.isEmpty() ? nullToDash(null) : String.join("，", parts)).append("\n");
        } catch (Exception e) {
            log.debug("Failed to parse reviewerPolicy: {}", e.getMessage());
        }
    }

    /** 渲染回滚计划（PrTask.rollbackPlan JSON）。 */
    private void appendRollbackPlan(StringBuilder sb, String rollbackPlanJson) {
        if (rollbackPlanJson == null || rollbackPlanJson.isBlank()) return;
        try {
            Map<String, Object> plan = objectMapper.readValue(
                    rollbackPlanJson, new TypeReference<Map<String, Object>>() {});
            sb.append("  - 回滚计划：策略 ").append(nullToDash(str(plan.get("strategy"))));
            if (plan.get("rollbackTag") != null) {
                sb.append("，回滚标签 `").append(plan.get("rollbackTag")).append("`");
            }
            if (Boolean.TRUE.equals(plan.get("dbBackupRequired"))) {
                sb.append("，需数据库备份");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Failed to parse rollbackPlan: {}", e.getMessage());
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    // ==================== 内部辅助 ====================

    private void appendNodeGroup(StringBuilder sb, String label, List<String> ids) {
        List<String> names = orEmpty(ids).stream()
                .map(this::nodeDisplay)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (names.isEmpty()) return;
        sb.append("- **").append(label).append("**：").append(String.join("、", names)).append("\n");
    }

    private String nodeDisplay(String nodeId) {
        return neo4jGraphDao.findNodeById(nodeId)
                .map(n -> n.getDisplayName() != null && !n.getDisplayName().isBlank()
                        ? n.getDisplayName() : n.getNodeName())
                .orElse(null);
    }

    private String firstNodeName(List<String> ids) {
        for (String id : orEmpty(ids)) {
            String name = nodeDisplay(id);
            if (name != null) return name;
        }
        return "-";
    }

    /** 拉取一组节点的证据，按 evidenceType 分组为可读条目。 */
    private Map<String, List<String>> collectEvidence(List<String> nodeIds) {
        Map<String, List<String>> byType = new LinkedHashMap<>();
        Set<String> seenEvidenceIds = new HashSet<>();
        for (String nodeId : nodeIds.stream().distinct().toList()) {
            List<NodeEvidence> links = nodeEvidenceRepository.lambdaQuery()
                    .eq(NodeEvidence::getNodeId, nodeId).list();
            for (NodeEvidence link : links) {
                if (link.getEvidenceId() == null || !seenEvidenceIds.add(link.getEvidenceId())) continue;
                Evidence ev = evidenceRepository.selectById(link.getEvidenceId());
                if (ev == null) continue;
                String type = ev.getEvidenceType() != null ? ev.getEvidenceType() : "unknown";
                String locator = ev.getSourcePath() != null ? ev.getSourcePath() : nullToDash(ev.getSourceName());
                if (ev.getStartLine() != null) {
                    locator += ":" + ev.getStartLine() + (ev.getEndLine() != null ? "-" + ev.getEndLine() : "");
                }
                byType.computeIfAbsent(type, k -> new ArrayList<>()).add(locator);
            }
        }
        return byType;
    }

    private void appendTestResults(StringBuilder sb, List<String> testCaseIds) {
        List<String> ids = orEmpty(testCaseIds);
        if (ids.isEmpty()) {
            sb.append("_暂无关联测试_\n\n");
            return;
        }
        sb.append("| 用例 | 类型 | 最近结果 |\n|------|------|------|\n");
        for (String caseId : ids) {
            TestCase tc = testCaseRepository.selectById(caseId);
            if (tc == null) continue;
            TestResult latest = testResultRepository.lambdaQuery()
                    .eq(TestResult::getTestCaseId, caseId)
                    .orderByDesc(TestResult::getExecutedAt)
                    .last("LIMIT 1")
                    .one();
            sb.append(String.format("| %s | %s | %s |\n",
                    nullToDash(tc.getCaseName()), nullToDash(tc.getCaseType()),
                    latest != null ? nullToDash(latest.getResultStatus()) : "未执行"));
        }
        sb.append("\n");
    }

    private static String nullToDash(String s) {
        return s != null && !s.isBlank() ? s : "-";
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : Collections.emptyList();
    }
}
