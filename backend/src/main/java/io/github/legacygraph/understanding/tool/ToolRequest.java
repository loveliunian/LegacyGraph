package io.github.legacygraph.understanding.tool;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 工具执行请求。
 */
@Data
@Builder
public class ToolRequest {
    /** 项目 ID */
    private String projectId;
    /** 版本 ID */
    private String versionId;
    /** 操作类型 */
    private ToolCapability operation;
    /** 查询参数（符号名、路径等） */
    private Map<String, Object> parameters;
    /** 工作目录 */
    private String workingDir;
}
