package io.github.legacygraph.understanding.tool;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * 工具健康状态结果。
 */
@Data
@Builder
public class ToolHealth {
    /** 工具名称 */
    private String toolName;
    /** 工具类型 */
    private ToolKind toolKind;
    /** 健康状态 */
    private ToolStatus status;
    /** 可用能力 */
    private Set<ToolCapability> capabilities;
    /** 索引新鲜度：FRESH / STALE / UNKNOWN */
    private String indexFreshness;
    /** 附加消息 */
    private String message;
}
