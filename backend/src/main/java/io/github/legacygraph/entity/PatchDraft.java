package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 补丁草案实体（阶段二-2.2）。
 * <p>
 * 将已审批的 Solution 转换为可验证的 PatchDraft，每个 Draft 包含文件级 diff、
 * 证据引用、风险评估。处于 Solution 与 ChangeTask/PatchPlan 之间的关键中间层，
 * 让每一步都有证据、审计、验证和回放能力。
 * </p>
 * <p>状态机：DRAFT → VALIDATED → MATERIALIZED → EXPIRED</p>
 */
@Data
@TableName("lg_patch_draft")
public class PatchDraft {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联的方案 ID */
    private String solutionId;

    private String projectId;

    private String versionId;

    /**
     * 状态：DRAFT / VALIDATED / MATERIALIZED / EXPIRED
     */
    private String status;

    /** 文件级变更列表（JSON 数组字符串，DraftFile 序列化） */
    private String filesJson;

    /** 风险等级：LOW / MEDIUM / HIGH */
    private String riskLevel;

    /** 生成来源：llm / manual */
    private String generatedBy;

    /** 校验报告（JSON 字符串，PatchValidationReport 序列化） */
    private String validationReportJson;

    private LocalDateTime createdAt;

    private LocalDateTime validatedAt;

    private LocalDateTime materializedAt;
}
