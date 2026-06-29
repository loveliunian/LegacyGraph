package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.trace.TraceIngestRequest;
import io.github.legacygraph.dto.trace.TraceTopology;
import io.github.legacygraph.entity.RuntimeTrace;
import io.github.legacygraph.service.TraceIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 运行时链路控制器（P2-1）
 * 接收 span 上报，提供运行时调用拓扑与链路列表查询。
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/runtime")
@Tag(name = "运行时链路", description = "trace span 上报与运行时拓扑查询")
public class TraceController {

    private final TraceIngestionService traceIngestionService;

    public TraceController(TraceIngestionService traceIngestionService) {
        this.traceIngestionService = traceIngestionService;
    }

    @PostMapping("/traces")
    @Operation(summary = "上报运行时 span", description = "接收 OpenTelemetry / 日志采样导出的 span 批量上报")
    public Result<Map<String, Object>> ingest(
            @PathVariable String projectId,
            @RequestBody TraceIngestRequest request) {
        int count = traceIngestionService.ingest(projectId, request);
        return Result.success(Map.of("ingested", count));
    }

    @GetMapping("/topology")
    @Operation(summary = "获取运行时服务拓扑", description = "由已上报 span 聚合的服务节点与调用边")
    public Result<TraceTopology> getTopology(
            @PathVariable String projectId,
            @RequestParam(required = false) String versionId) {
        return Result.success(traceIngestionService.getTopology(projectId, versionId));
    }

    @GetMapping("/traces")
    @Operation(summary = "获取最近链路列表", description = "返回最近上报的 span 记录")
    public Result<List<RuntimeTrace>> listTraces(
            @PathVariable String projectId,
            @RequestParam(required = false) String versionId,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(traceIngestionService.listRecentTraces(projectId, versionId, limit));
    }
}
