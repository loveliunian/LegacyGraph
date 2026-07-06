package io.github.legacygraph.dto.systemoverview;

import lombok.Builder;
import lombok.Data;

/**
 * 系统关系总览底座导入结果。
 */
@Data
@Builder
public class SystemOverviewIngestResult {

    private String projectId;
    private String versionId;

    /** 写入向量数 */
    private int vectorCount;

    /** 写入 Claim 数 */
    private int claimCount;

    /** 写入 FAQ 语义缓存数 */
    private int faqCount;

    /** 跳过数（空内容、缺字段等） */
    private int skipped;
}
