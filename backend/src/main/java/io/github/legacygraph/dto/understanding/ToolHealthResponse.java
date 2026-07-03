package io.github.legacygraph.dto.understanding;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工具健康状态响应 DTO。
 */
@Data
@Builder
public class ToolHealthResponse {

    /** 项目 ID */
    private String projectId;

    /** 工具健康状态列表 */
    private List<ToolHealthDto> tools;

    @Data
    @Builder
    public static class ToolHealthDto {
        private String toolName;
        private String toolKind;
        private String status;
        private List<String> capabilities;
        private String indexFreshness;
        private String message;
    }
}
