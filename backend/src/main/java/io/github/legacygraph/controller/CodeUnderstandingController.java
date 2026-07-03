package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.understanding.*;
import io.github.legacygraph.understanding.CodeUnderstandingOrchestrator;
import io.github.legacygraph.understanding.CodeUnderstandingReportService;
import io.github.legacygraph.understanding.tool.ToolHealth;
import io.github.legacygraph.understanding.tool.ToolHealthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 代码理解控制器 —— 提供工具健康检查、报告生成和查询 API。
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/understanding")
@RequiredArgsConstructor
public class CodeUnderstandingController {

    private final ToolHealthService toolHealthService;
    private final CodeUnderstandingOrchestrator orchestrator;
    private final CodeUnderstandingReportService reportService;

    /** 内存中存储任务状态（MVP 阶段，后续可接入 Redis 或 DB） */
    private final Map<String, CodeUnderstandingTaskResult> taskStore = new LinkedHashMap<>();

    /**
     * GET /api/lg/projects/{projectId}/understanding/tool-health
     * 查询所有可用工具的健康状态。
     */
    @GetMapping("/tool-health")
    public Result<ToolHealthResponse> getToolHealth(@PathVariable String projectId) {
        List<ToolHealth> healthList = toolHealthService.checkAllTools(projectId);

        List<ToolHealthResponse.ToolHealthDto> tools = healthList.stream()
                .map(h -> ToolHealthResponse.ToolHealthDto.builder()
                        .toolName(h.getToolName())
                        .toolKind(h.getToolKind() != null ? h.getToolKind().name() : null)
                        .status(h.getStatus() != null ? h.getStatus().name() : "UNKNOWN")
                        .capabilities(h.getCapabilities() != null
                                ? h.getCapabilities().stream().map(Enum::name).toList()
                                : List.of())
                        .indexFreshness(h.getIndexFreshness())
                        .message(h.getMessage())
                        .build())
                .toList();

        ToolHealthResponse response = ToolHealthResponse.builder()
                .projectId(projectId)
                .tools(tools)
                .build();

        return Result.success(response);
    }

    /**
     * POST /api/lg/projects/{projectId}/understanding/reports
     * 创建代码理解报告。
     */
    @PostMapping("/reports")
    public Result<?> createReport(@PathVariable String projectId,
                                   @RequestBody CodeUnderstandingRequest request) {
        // 校验非法路径
        if (request.getToolPolicy() != null && request.getToolPolicy().isAllowExternalNetwork()) {
            return Result.badRequest("MVP 模式下不允许外部网络访问");
        }

        // 校验超预算请求
        if (request.getToolPolicy() != null && request.getToolPolicy().getMaxToolRuns() > 100) {
            return Result.badRequest("maxToolRuns 不能超过 100");
        }

        try {
            CodeUnderstandingTaskResult result = orchestrator.execute(projectId, request);
            taskStore.put(result.getTaskId(), result);

            CreateUnderstandingReportResponse response = CreateUnderstandingReportResponse.builder()
                    .taskId(result.getTaskId())
                    .status(result.getStatus())
                    .reportId(result.getReportId())
                    .toolRuns(result.getToolRuns())
                    .evidenceCount(result.getEvidenceCount())
                    .claimCount(result.getClaimCount())
                    .pendingConfirmCount(result.getPendingConfirmCount())
                    .downloadUrl(result.getDownloadUrl())
                    .toolStatus(buildToolStatusMap(projectId))
                    .build();

            return Result.success(response);
        } catch (Exception e) {
            log.error("创建代码理解报告失败: projectId={}", projectId, e);
            return Result.error("报告生成失败: " + e.getMessage());
        }
    }

    private Map<String, String> buildToolStatusMap(String projectId) {
        Map<String, String> toolStatus = new LinkedHashMap<>();
        try {
            List<ToolHealth> healthList = toolHealthService.checkAllTools(projectId);
            if (healthList != null) {
                for (ToolHealth health : healthList) {
                    if (health.getToolName() == null) {
                        continue;
                    }
                    String status = health.getStatus() != null ? health.getStatus().name() : "UNKNOWN";
                    toolStatus.put(health.getToolName(), status);
                }
            }
        } catch (Exception e) {
            log.warn("构建工具状态摘要失败: projectId={}, err={}", projectId, e.getMessage());
            toolStatus.put("tool-health", "UNAVAILABLE");
        }
        if (toolStatus.isEmpty()) {
            toolStatus.put("local-fallback", "READY");
        }
        return toolStatus;
    }

    /**
     * GET /api/lg/projects/{projectId}/understanding/reports/{taskId}
     * 查询报告任务状态。
     */
    @GetMapping("/reports/{taskId}")
    public Result<CodeUnderstandingTaskResult> getReport(@PathVariable String projectId,
                                                          @PathVariable String taskId) {
        CodeUnderstandingTaskResult result = taskStore.get(taskId);
        if (result == null) {
            return Result.error("任务不存在");
        }
        return Result.success(result);
    }

    /**
     * GET /api/lg/projects/{projectId}/understanding/reports/{taskId}/download
     * 下载代码理解报告（Markdown）。
     */
    @GetMapping("/reports/{taskId}/download")
    public ResponseEntity<byte[]> downloadReport(@PathVariable String projectId,
                                                  @PathVariable String taskId,
                                                  @RequestParam(defaultValue = "MD") String format) {
        CodeUnderstandingTaskResult result = taskStore.get(taskId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }

        if (!"MD".equalsIgnoreCase(format)) {
            return ResponseEntity.badRequest().build();
        }

        String markdown = reportService.generateMarkdown(projectId, taskId, result, null);
        byte[] content = markdown.getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_MARKDOWN);
        headers.setContentDispositionFormData("attachment", "code-understanding-report.md");

        return ResponseEntity.ok().headers(headers).body(content);
    }
}
