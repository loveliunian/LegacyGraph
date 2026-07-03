package io.github.legacygraph.understanding.tool;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 工具执行结果。
 */
@Data
@Builder
public class ToolResult {
    /** 工具名称 */
    private String toolName;
    /** 工具类型 */
    private ToolKind toolKind;
    /** 操作类型 */
    private ToolCapability operation;
    /** 执行状态：SUCCESS / FAILED / UNAVAILABLE / TIMEOUT / DENIED */
    private String status;
    /** 退出码 */
    private Integer exitCode;
    /** 耗时（毫秒） */
    private Long elapsedMs;
    /** 索引新鲜度 */
    private String indexFreshness;
    /** 输出 SHA256 */
    private String stdoutSha256;
    /** 输出摘要（截断） */
    private String stdoutExcerpt;
    /** 错误摘要 */
    private String errorExcerpt;
    /** 证据列表（归一化后） */
    private List<Map<String, Object>> evidenceRecords;
}
