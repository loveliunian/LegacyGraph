package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运行时调用链 span 表实体
 * 接收 OpenTelemetry / 日志采样上报的 span，作为运行时验证证据来源。
 */
@Data
@TableName("lg_runtime_trace")
public class RuntimeTrace {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionId;

    /** 同一次调用链的 traceId */
    private String traceId;
    /** 当前 span id */
    private String spanId;
    /** 父 span id（根 span 为空） */
    private String parentSpanId;

    /** 服务名（拓扑节点） */
    private String serviceName;
    /** 操作名，通常为 HTTP method+path 或方法签名 */
    private String operationName;
    /** SERVER / CLIENT / INTERNAL */
    private String spanKind;

    /** 耗时（毫秒） */
    private Long durationMs;
    /** OK / ERROR */
    private String status;

    /** 调用发生时间 */
    private LocalDateTime startedAt;

    private LocalDateTime createdAt;

    private Integer deleted;
}
