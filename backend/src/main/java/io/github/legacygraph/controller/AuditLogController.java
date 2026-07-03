package io.github.legacygraph.controller;

import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.AuditLog;
import io.github.legacygraph.service.system.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 审计日志控制器
 */
@RestController
@RequestMapping("/lg/audit")
@Tag(name = "审计日志", description = "操作日志查询、清空等")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping("/list")
    @Operation(summary = "分页查询审计日志")
    @Log(value = "查询审计日志列表", type = Log.OperationType.QUERY)
    public Result<PageResult<AuditLog>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String operatorName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        PageResult<AuditLog> result = auditLogService.list(pageNum, pageSize,
                operation, operatorName, status, startTime, endTime);
        return Result.success(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取审计日志详情")
    @Log(value = "查看审计日志详情", type = Log.OperationType.QUERY)
    public Result<AuditLog> getById(@PathVariable Long id) {
        AuditLog log = auditLogService.getById(id);
        return Result.success(log);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除单条日志")
    @Log(value = "删除审计日志", type = Log.OperationType.DELETE)
    public Result<Void> delete(@PathVariable Long id) {
        boolean deleted = auditLogService.delete(id);
        if (deleted) {
            return Result.success();
        } else {
            return Result.error("删除失败，日志不存在");
        }
    }

    @DeleteMapping("/clear")
    @Operation(summary = "清空所有日志")
    @Log(value = "清空审计日志", type = Log.OperationType.DELETE)
    public Result<Void> clear() {
        auditLogService.clear();
        return Result.success();
    }

    @GetMapping("/stats/count")
    @Operation(summary = "获取日志统计")
    public Result<java.util.Map<String, Long>> stats() {
        long total = auditLogService.count();
        long today = auditLogService.countToday();
        return Result.success(java.util.Map.of(
            "total", total,
            "today", today
        ));
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "下载审计日志详情", description = "以 JSON 格式下载单条审计日志")
    public org.springframework.http.ResponseEntity<byte[]> download(
            @PathVariable Long id) {
        AuditLog log = auditLogService.getById(id);
        if (log == null) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(log);
            byte[] data = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return org.springframework.http.ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=audit-log-" + id + ".json")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }
}
