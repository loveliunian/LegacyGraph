package io.github.legacygraph.understanding.tool;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工具执行策略 —— 控制预算、安全和权限。
 */
@Data
@Builder
public class ToolPolicy {
    /** 允许的工具类型 */
    private List<ToolKind> enabledToolKinds;
    /** 允许的具体工具 */
    private List<String> allowedTools;
    /** 执行模式：READ_ONLY */
    @Builder.Default
    private String executionMode = "READ_ONLY";
    /** 是否允许外部网络 */
    @Builder.Default
    private boolean allowExternalNetwork = false;
    /** 是否允许 AI 推断 */
    @Builder.Default
    private boolean allowAiInference = true;
    /** 最大读取文件数 */
    @Builder.Default
    private int maxFilesToRead = 20;
    /** 最大工具调用次数 */
    @Builder.Default
    private int maxToolRuns = 30;
    /** 最大执行时间（秒） */
    @Builder.Default
    private int maxSeconds = 180;
    /** 最大输出字节数 */
    @Builder.Default
    private int maxOutputBytes = 200_000;
}
