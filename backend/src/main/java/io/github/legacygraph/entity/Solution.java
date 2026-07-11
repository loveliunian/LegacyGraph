package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 方案主表实体（Task 10）。
 * <p>基于需求分析与影响子图由 LLM 生成的文件级实施方案。
 * 状态流转：DRAFT → READY_FOR_REVIEW / NEEDS_INPUT → APPROVED / REJECTED。</p>
 */
@Data
@TableName("lg_solution")
public class Solution {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    /** 关联的需求 ID（lg_requirement.id） */
    private String requirementId;

    /**
     * 状态：DRAFT / READY_FOR_REVIEW / NEEDS_INPUT / APPROVED / REJECTED
     */
    private String status;

    /** 方案总览摘要（LLM 输出的 summary） */
    private String summary;

    /** 生成方案时使用的需求分析 JSON 快照（用于 verify 阶段重建 openQuestions 检查） */
    private String analysisJson;

    /** 生成方案时使用的影响子图 JSON 快照（用于 verify 阶段重建高风险节点覆盖检查） */
    private String impactResultJson;

    /** 校验错误信息（NEEDS_INPUT 时非空，JSON 数组字符串） */
    private String verificationErrors;

    /** 成本估算 JSON（SolutionPlan.estimatedCost 的序列化） */
    private String estimatedCostJson;

    /** 风险评估 JSON（SolutionPlan.riskAssessment 的序列化） */
    private String riskAssessmentJson;

    /** 关联的变更任务 ID（方案转执行后填充） */
    private String changeTaskId;

    /** 最终评审人 */
    private String reviewer;

    /** 评审意见 */
    private String reviewComment;

    /** 最终评审时间 */
    private LocalDateTime reviewedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
