package io.github.legacygraph.understanding.tool;

import lombok.Builder;
import lombok.Data;

/**
 * 工具上下文 —— 健康检查和执行时的项目环境。
 */
@Data
@Builder
public class ToolContext {
    /** 项目 ID */
    private String projectId;
    /** 项目根目录 */
    private String projectRoot;
    /** 工作目录白名单 */
    private java.util.List<String> workspaceWhitelist;
}
