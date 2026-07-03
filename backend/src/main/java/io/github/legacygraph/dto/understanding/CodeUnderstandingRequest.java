package io.github.legacygraph.dto.understanding;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * 代码理解报告请求 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeUnderstandingRequest {

    /** 版本 ID */
    private String versionId;

    /** 用户问题 */
    private String question;

    /** 分析范围 */
    private Scope scope;

    /** 报告类型 */
    @Builder.Default
    private String reportType = "CODE_UNDERSTANDING";

    /** 导出格式 */
    @Builder.Default
    private String format = "MD";

    /** 工具策略 */
    @Builder.Default
    private ToolPolicyDto toolPolicy = new ToolPolicyDto();

    /**
     * 分析范围。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scope {
        /** 分析路径 */
        private List<String> paths;
        /** 分析符号 */
        private List<String> symbols;
        /** 分析功能键 */
        private List<String> featureKeys;
    }

    /**
     * 工具策略 DTO。
     */
    @Data
    public static class ToolPolicyDto {
        private List<String> enabledToolKinds;
        private List<String> allowedTools;
        private String executionMode = "READ_ONLY";
        private boolean allowExternalNetwork = false;
        private boolean allowAiInference = true;
        private int maxFilesToRead = 20;
        private int maxToolRuns = 30;
        private int maxSeconds = 180;
        private int maxOutputBytes = 200_000;
    }
}
