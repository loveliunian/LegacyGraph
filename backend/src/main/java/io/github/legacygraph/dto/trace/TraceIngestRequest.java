package io.github.legacygraph.dto.trace;

import lombok.Data;

import java.util.List;

/**
 * 运行时 span 上报请求
 * 兼容 OpenTelemetry / 日志采样导出的简化 span 结构。
 */
@Data
public class TraceIngestRequest {

    private String versionId;
    private List<SpanDto> spans;

    @Data
    public static class SpanDto {
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private String serviceName;
        private String operationName;
        private String spanKind;
        private Long durationMs;
        private String status;     // OK / ERROR
        private Long startEpochMs;  // span 开始时间（epoch 毫秒），可空
    }
}
