package io.github.legacygraph.service.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.ToolEvidenceEntity;
import io.github.legacygraph.entity.ToolRunEntity;
import io.github.legacygraph.repository.ToolEvidenceRepository;
import io.github.legacygraph.repository.ToolRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 外部工具证据导出服务 — 从 lg_tool_run / lg_tool_evidence 导出 Markdown 摘要，
 * 供 {@link ScanArtifactPublisher} 发布到 /docs/legacygraph/。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalToolEvidenceExporter {

    private final ToolRunRepository toolRunRepository;
    private final ToolEvidenceRepository toolEvidenceRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 按 projectId + versionId 导出外部工具运行和证据摘要为 Markdown。
     */
    public String exportMarkdown(String projectId, String versionId) {
        List<ToolRunEntity> runs = queryRuns(projectId, versionId);
        if (runs.isEmpty()) {
            return "";
        }

        List<String> runIds = runs.stream().map(ToolRunEntity::getId).collect(Collectors.toList());
        List<ToolEvidenceEntity> evidences = queryEvidences(runIds);

        StringBuilder sb = new StringBuilder();
        sb.append("# 外部工具证据摘要\n\n");
        sb.append(String.format("**项目ID:** %s%n", projectId));
        sb.append(String.format("**扫描版本:** %s%n", versionId != null ? versionId : "default"));
        sb.append(String.format("**生成时间:** %s%n%n", LocalDateTime.now().format(DATE_FORMATTER)));

        // 工具运行概览
        sb.append("## 1. 工具运行概览\n\n");
        sb.append("| 工具名称 | 类型 | 操作 | 状态 | 耗时(ms) | 运行时间 |\n");
        sb.append("|----------|------|------|------|----------|----------|\n");
        for (ToolRunEntity run : runs) {
            sb.append(String.format("| %s | %s | %s | %s | %d | %s |%n",
                    run.getToolName() != null ? run.getToolName() : "-",
                    run.getToolKind() != null ? run.getToolKind() : "-",
                    run.getOperation() != null ? run.getOperation() : "-",
                    run.getStatus() != null ? run.getStatus() : "-",
                    run.getElapsedMs() != null ? run.getElapsedMs() : 0,
                    run.getCreatedAt() != null ? run.getCreatedAt().format(DATE_FORMATTER) : "-"));
        }
        sb.append("\n");

        // 证据清单
        if (!evidences.isEmpty()) {
            sb.append("## 2. 证据清单\n\n");
            sb.append("| # | 证据类型 | 源文件 | 符号 | 行号 | 置信度 | 摘要 |\n");
            sb.append("|---|----------|--------|------|------|--------|------|\n");
            int i = 1;
            for (ToolEvidenceEntity ev : evidences) {
                String excerpt = ev.getExcerpt() != null
                        ? (ev.getExcerpt().length() > 80 ? ev.getExcerpt().substring(0, 80) + "..." : ev.getExcerpt())
                        : "-";
                String lineRange = ev.getLineStart() != null
                        ? (ev.getLineEnd() != null ? ev.getLineStart() + "-" + ev.getLineEnd() : String.valueOf(ev.getLineStart()))
                        : "-";
                sb.append(String.format("| %d | %s | %s | %s | %s | %.0f%% | %s |%n",
                        i++,
                        ev.getEvidenceType() != null ? ev.getEvidenceType() : "-",
                        ev.getSourcePath() != null ? ev.getSourcePath() : "-",
                        ev.getSymbolQn() != null ? ev.getSymbolQn() : "-",
                        lineRange,
                        ev.getConfidence() != null ? ev.getConfidence() * 100 : 50,
                        excerpt));
            }
            sb.append("\n");
        }

        // 异常工具
        List<ToolRunEntity> failedRuns = runs.stream()
                .filter(r -> !"SUCCESS".equals(r.getStatus()))
                .toList();
        if (!failedRuns.isEmpty()) {
            sb.append("## 3. 异常工具\n\n");
            for (ToolRunEntity run : failedRuns) {
                sb.append(String.format("- **%s** (%s): %s%n",
                        run.getToolName(), run.getStatus(),
                        run.getErrorExcerpt() != null ? run.getErrorExcerpt() : "无错误详情"));
            }
            sb.append("\n");
        }

        sb.append("\n---\n");
        sb.append("*由 LegacyGraph 外部工具证据导出器自动生成*");
        return sb.toString();
    }

    private List<ToolRunEntity> queryRuns(String projectId, String versionId) {
        try {
            LambdaQueryWrapper<ToolRunEntity> wrapper = new LambdaQueryWrapper<ToolRunEntity>()
                    .eq(ToolRunEntity::getProjectId, projectId);
            if (versionId != null && !versionId.isBlank()) {
                wrapper.eq(ToolRunEntity::getVersionId, versionId);
            }
            wrapper.orderByDesc(ToolRunEntity::getCreatedAt);
            return toolRunRepository.selectList(wrapper);
        } catch (Exception e) {
            log.warn("查询工具运行记录失败 projectId={}, versionId={}: {}", projectId, versionId, e.getMessage());
            return List.of();
        }
    }

    private List<ToolEvidenceEntity> queryEvidences(List<String> runIds) {
        if (runIds.isEmpty()) return List.of();
        try {
            return toolEvidenceRepository.selectList(
                    new LambdaQueryWrapper<ToolEvidenceEntity>()
                            .in(ToolEvidenceEntity::getToolRunId, runIds)
                            .orderByDesc(ToolEvidenceEntity::getConfidence));
        } catch (Exception e) {
            log.warn("查询工具证据失败 runIds={}: {}", runIds, e.getMessage());
            return List.of();
        }
    }
}
