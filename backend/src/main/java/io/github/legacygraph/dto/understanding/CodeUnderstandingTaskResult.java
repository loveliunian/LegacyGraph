package io.github.legacygraph.dto.understanding;

import lombok.Builder;
import lombok.Data;

/**
 * 代码理解任务结果 DTO。
 */
@Data
@Builder
public class CodeUnderstandingTaskResult {

    /** 任务 ID */
    private String taskId;

    /** 任务状态：RUNNING / SUCCESS / FAILED */
    private String status;

    /** 报告 ID */
    private String reportId;

    /** 工具运行次数 */
    private int toolRuns;

    /** 证据数量 */
    private int evidenceCount;

    /** Claim 数量 */
    private int claimCount;

    /** 待确认 Claim 数量 */
    private int pendingConfirmCount;

    /** 下载 URL */
    private String downloadUrl;
}
