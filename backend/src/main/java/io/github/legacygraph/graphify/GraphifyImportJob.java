package io.github.legacygraph.graphify;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Graphify 导入作业实体。
 * <p>
 * 跟踪从外部 Graphify 工具导入图谱数据的完整生命周期，支持排队、运行、失败、重试、取消和回滚。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphifyImportJob {

    /**
     * 作业状态枚举。
     */
    public enum Status {
        /** 已排队，等待执行 */
        QUEUED,
        /** 正在执行导入 */
        RUNNING,
        /** 导入成功完成 */
        IMPORTED,
        /** 导入失败 */
        FAILED,
        /** 已取消 */
        CANCELLED
    }

    /** 作业唯一标识符 */
    private String jobId;

    /** 项目 ID */
    private String projectId;

    /** 扫描版本 ID */
    private String versionId;

    /** 项目根目录路径 */
    private String projectRoot;

    /** 分支名称 */
    private String branchName;

    /** 源码 commit hash */
    private String sourceCommit;

    /** Graphify 工具版本 */
    private String graphifyVersion;

    /** 当前尝试次数 */
    @Builder.Default
    private int attempt = 0;

    /** 最大允许尝试次数 */
    @Builder.Default
    private int maxAttempts = 3;

    /** 作业创建时间 */
    private LocalDateTime createdAt;

    /** 作业开始执行时间 */
    private LocalDateTime startedAt;

    /** 作业完成时间（成功或失败） */
    private LocalDateTime finishedAt;

    /** 错误信息（仅在失败时） */
    private String errorMessage;

    /** 当前状态 */
    @Builder.Default
    private Status status = Status.QUEUED;

    /** 导入的节点数量 */
    private Integer importedNodes;

    /** 导入的边数量 */
    private Integer importedEdges;

    /** 导入的证据数量 */
    private Integer importedEvidence;

    /** 兼容性报告 ID */
    private String compatibilityReportId;

    /**
     * 检查作业是否可以重试。
     *
     * @return true 如果作业失败且尝试次数未达上限
     */
    public boolean canRetry() {
        return status == Status.FAILED && attempt < maxAttempts;
    }

    /**
     * 检查作业是否已完成（成功、失败或取消）。
     *
     * @return true 如果作业已终止
     */
    public boolean isTerminated() {
        return status == Status.IMPORTED
                || status == Status.FAILED
                || status == Status.CANCELLED;
    }
}
