package io.github.legacygraph.dto.understanding;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 创建理解报告响应 DTO。
 */
@Data
@Builder
public class CreateUnderstandingReportResponse {

    /** 任务 ID */
    private String taskId;

    /** 任务状态 */
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

    /** 工具状态 */
    private Map<String, String> toolStatus;
}
