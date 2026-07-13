package io.github.legacygraph.dto.sandbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 沙箱执行结果（阶段三-3.1）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxResult {

    /** 是否全部通过 */
    private boolean success;

    /** 构建输出日志 */
    private String buildOutput;

    /** 测试结果列表 */
    private List<TestResult> testResults;

    /** 静态分析违规列表 */
    private List<Violation> violations;

    /** 指标（如编译时间、测试通过率等） */
    private Map<String, String> metrics;

    /** 失败原因（success=false 时填充） */
    private String failureReason;

    /**
     * 单个测试结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestResult {
        /** 测试名称 */
        private String testName;
        /** 测试类型：UNIT / API / DB / E2E */
        private String testType;
        /** 是否通过 */
        private boolean passed;
        /** 耗时（毫秒） */
        private long durationMs;
        /** 失败信息 */
        private String failureMessage;
    }

    /**
     * 静态分析违规。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Violation {
        /** 规则名 */
        private String rule;
        /** 严重级别：ERROR / WARN / INFO */
        private String severity;
        /** 文件路径 */
        private String filePath;
        /** 行号 */
        private int lineNumber;
        /** 违规消息 */
        private String message;
    }
}
