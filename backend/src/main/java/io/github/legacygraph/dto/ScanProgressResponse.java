package io.github.legacygraph.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 扫描进度响应DTO
 * <p>
 * 包含版本级别进度 + 各阶段详情 + 整体计划信息，
 * 供前端版本详情页展示扫描过程的每个环节及其进度。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScanProgressResponse {

    private String versionId;
    private String status;
    /** 整体进度百分比 0-100 */
    private Integer progress;
    /** 各阶段详情 */
    private List<TaskProgress> tasks;
    /** 扫描阶段顺序定义（所有可能的阶段，含未开始的） */
    private List<ScanPhase> phases;
    /** 当前正在进行的阶段序号（-1 表示未开始或已完成） */
    private int currentPhaseIndex;
    /** 预估剩余时间（秒），-1 表示无法预估 */
    private Long estimatedSecondsRemaining;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskProgress {
        /** 阶段 taskType，如 DB_DISCOVERY */
        private String taskType;
        /** 阶段中文名 */
        private String phaseName;
        /** 当前状态：PENDING / RUNNING / SUCCESS / FAILED / SKIPPED */
        private String status;
        /** 已发现的事实数 */
        private Integer factCount;
        /** 本阶段需处理的项总数 */
        private Integer totalItems;
        /** 本阶段已处理项数 */
        private Integer processedItems;
        /** 当前正在处理的项名称（文件路径/表名等） */
        private String currentItem;
        /** 阶段开始时间 */
        private LocalDateTime startedAt;
        /** 阶段结束时间 */
        private LocalDateTime finishedAt;
        /** 本阶段预估剩余时间（秒），-1 表示无法预估 */
        private Long estimatedSecondsRemaining;
        /** 子阶段列表（仅 AI_ORCHESTRATION 使用，展开显示 AI 内部各子环节） */
        private List<TaskProgress> subPhases;
    }
}
