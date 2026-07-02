package io.github.legacygraph.service;

import io.github.legacygraph.dto.report.ScanResearchReport;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.KnowledgeClaimRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 扫描研究报告生成服务 — 生成 Markdown 格式的扫描研究报告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanResearchReportService {

    private final ScanVersionRepository scanVersionRepository;
    private final ScanTaskRepository scanTaskRepository;
    private final KnowledgeClaimRepository claimRepository;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 生成 Markdown 格式的扫描研究报告。
     */
    public String generateMarkdown(String projectId, String versionId) {
        ScanVersion version = scanVersionRepository.selectById(versionId);
        List<ScanTask> tasks = scanTaskRepository.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ScanTask>()
                        .eq(ScanTask::getVersionId, versionId));
        List<KnowledgeClaim> claims = claimRepository.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeClaim>()
                        .eq(KnowledgeClaim::getVersionId, versionId));

        StringBuilder sb = new StringBuilder();
        sb.append("# 资料扫描与图谱构建研究报告\n\n");
        sb.append("> 生成时间: ").append(LocalDateTime.now().format(DATE_FMT)).append("\n");
        sb.append("> 项目ID: ").append(projectId).append("\n");
        sb.append("> 版本ID: ").append(versionId).append("\n\n");

        // 1. 扫描输入
        sb.append("## 1. 扫描输入\n\n");
        if (version != null) {
            sb.append("- 扫描状态: ").append(version.getScanStatus() != null ? version.getScanStatus() : "N/A").append("\n");
            sb.append("- 开始时间: ").append(version.getStartedAt() != null ? version.getStartedAt().format(DATE_FMT) : "N/A").append("\n");
            sb.append("- 结束时间: ").append(version.getFinishedAt() != null ? version.getFinishedAt().format(DATE_FMT) : "N/A").append("\n");
        }
        sb.append("\n");

        // 2. Adapter 执行统计
        sb.append("## 2. Adapter 执行统计\n\n");
        if (tasks.isEmpty()) {
            sb.append("无扫描任务记录。\n");
        } else {
            sb.append("| 任务类型 | 状态 | 消息 |\n");
            sb.append("|---|---|---|\n");
            for (ScanTask task : tasks) {
                sb.append("| ").append(task.getTaskType())
                        .append(" | ").append(task.getTaskStatus())
                        .append(" | ").append(task.getOutputSummary() != null ? task.getOutputSummary() : "-")
                        .append(" |\n");
            }
        }
        sb.append("\n");

        // 3. 数据库扫描统计
        sb.append("## 3. 数据库扫描统计\n\n");
        if (version != null) {
            sb.append("- 节点数: ").append(version.getNodeCount() != null ? version.getNodeCount() : 0).append("\n");
            sb.append("- 边数: ").append(version.getEdgeCount() != null ? version.getEdgeCount() : 0).append("\n");
            sb.append("- 事实数: ").append(version.getFactCount() != null ? version.getFactCount() : 0).append("\n");
        }
        sb.append("\n");

        // 4. 图谱写入统计
        sb.append("## 4. 图谱写入统计\n\n");
        if (version != null) {
            sb.append("- 任务总数: ").append(version.getTaskTotal() != null ? version.getTaskTotal() : 0).append("\n");
            sb.append("- 成功任务: ").append(version.getTaskSuccess() != null ? version.getTaskSuccess() : 0).append("\n");
            sb.append("- 失败任务: ").append(version.getTaskFailed() != null ? version.getTaskFailed() : 0).append("\n");
        }
        sb.append("\n");

        // 5. Claim 统计
        sb.append("## 5. Claim 统计\n\n");
        if (claims.isEmpty()) {
            sb.append("无 Claim 记录。\n");
        } else {
            Map<String, Long> byStatus = new HashMap<>();
            Map<String, Long> bySourceType = new HashMap<>();
            for (KnowledgeClaim claim : claims) {
                byStatus.merge(claim.getStatus() != null ? claim.getStatus() : "UNKNOWN", 1L, Long::sum);
                bySourceType.merge(claim.getSourceType() != null ? claim.getSourceType() : "UNKNOWN", 1L, Long::sum);
            }
            sb.append("- Claim 总数: ").append(claims.size()).append("\n");
            sb.append("- 按状态统计:\n");
            for (var e : byStatus.entrySet()) {
                sb.append("  - ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
            sb.append("- 按来源统计:\n");
            for (var e : bySourceType.entrySet()) {
                boolean aiOnly = e.getKey().contains("AI") || e.getKey().contains("DOC_AI") || e.getKey().contains("CODE_AI");
                sb.append("  - ").append(e.getKey()).append(": ").append(e.getValue())
                        .append(aiOnly ? " ⚠️ AI候选\n" : "\n");
            }
        }
        sb.append("\n");

        // 6. 三类图谱覆盖
        sb.append("## 6. 三类图谱覆盖\n\n");
        sb.append("| 图谱类型 | 节点数 | 边数 | 备注 |\n");
        sb.append("|---|---|---|---|\n");
        sb.append("| 代码图谱 | N/A | N/A | 通过 API/Controller/Method/SQL 节点统计 |\n");
        sb.append("| 功能图谱 | N/A | N/A | 通过 Feature/Page/ApiEndpoint 节点统计 |\n");
        sb.append("| 业务图谱 | N/A | N/A | 通过 BusinessDomain/Process/Object 节点统计 |\n");
        sb.append("\n");

        // 7. 缺口清单
        sb.append("## 7. 缺口清单\n\n");
        if (claims.isEmpty()) {
            sb.append("无缺口记录（数据不足，建议执行 AI 编排后重新生成）。\n");
        } else {
            long aiPending = claims.stream()
                    .filter(c -> "PENDING_CONFIRM".equals(c.getStatus()))
                    .filter(c -> c.getSourceType() != null && c.getSourceType().contains("AI"))
                    .count();
            sb.append("- AI候选待确认: ").append(aiPending).append(" 条\n");
            sb.append("- 建议: 对 AI 候选进行人工审核或通过测试/运行时验证补证\n");
        }
        sb.append("\n");

        // 8. 不确定性声明
        sb.append("## 8. 不确定性声明\n\n");
        long aiOnlyClaims = claims.stream()
                .filter(c -> c.getSourceType() != null && c.getSourceType().contains("AI"))
                .count();
        long confirmedClaims = claims.stream()
                .filter(c -> "CONFIRMED".equals(c.getStatus()))
                .count();
        sb.append("- AI候选比例: ").append(claims.size() > 0
                ? String.format("%.1f%%", 100.0 * aiOnlyClaims / claims.size()) : "N/A").append("\n");
        sb.append("- 确定性事实比例: ").append(claims.size() > 0
                ? String.format("%.1f%%", 100.0 * confirmedClaims / claims.size()) : "N/A").append("\n");
        sb.append("- ⚠️ AI 来源的 Claim 仅作为候选知识，不建议直接用作迁移决策依据\n");

        return sb.toString();
    }
}
