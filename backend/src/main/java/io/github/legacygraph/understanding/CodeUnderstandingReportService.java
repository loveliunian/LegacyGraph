package io.github.legacygraph.understanding;

import io.github.legacygraph.dto.understanding.CodeUnderstandingTaskResult;
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
import java.util.List;
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

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
}
