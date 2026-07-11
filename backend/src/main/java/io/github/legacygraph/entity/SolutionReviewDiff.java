package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 方案评审差异实体 — 记录方案评审过程中每一步的修改差异。
 */
@Data
@TableName("lg_solution_review_diff")
public class SolutionReviewDiff {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 方案 ID */
    private String solutionId;

    /** 评审人 */
    private String reviewer;

    /** 步骤索引 */
    private Integer stepIndex;

    /** 差异类型（如 MODIFIED / ADDED / REMOVED 等） */
    private String diffType;

    /** 修改前摘要 */
    private String beforeSummary;

    /** 修改后摘要 */
    private String afterSummary;

    private LocalDateTime createdAt;
}
