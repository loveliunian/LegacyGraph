package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 变更任务实体（增强版2：ChangeTask 管道）。
 * <p>
 * 承载一次 bugfix/refactor/upgrade 的受控执行：从图谱定位影响子图，
 * 到生成 PatchPlan、运行验证门禁、生成 PR，全过程状态机化。
 * </p>
 * <p>状态流转（见设计文档 §ChangeTask 落地模块）：</p>
 * <pre>
 * OPEN → IMPACT_READY → PATCH_DRAFTED → VALIDATING
 *      → VALIDATION_PASSED / VALIDATION_FAILED
 *      → REVIEW_PENDING → PR_READY / PR_CREATED
 *      → MERGED / REJECTED / ROLLED_BACK
 * </pre>
 */
@Data
@TableName("lg_change_task")
public class ChangeTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String versionId;

    /** BUGFIX / REFACTOR / UPGRADE / ADD_COLUMN */
    private String taskType;

    private String title;

    /** 输入问题描述 / issue（JSON） */
    private String inputIssue;

    /** 影响子图快照（JSON：nodeIds/edgeIds/files） */
    private String impactedSubgraph;

    /** PatchPlan 草案（JSON） */
    private String proposal;

    /** LOW / MEDIUM / HIGH */
    private String riskLevel;

    /** 任务状态机，默认 OPEN */
    private String status;

    /** 乐观锁版本号（Phase 0-2），每次状态迁移 +1。对应 lg_change_task.version。 */
    @com.baomidou.mybatisplus.annotation.Version
    private Integer version;

    /** 关联的 AgentRun 合约ID，便于回放补丁生成过程 */
    private String agentRunId;

    /** 阶段三-3.1 漏点 ⑤：是否启用沙箱执行（执行门禁前先在沙箱中验证补丁） */
    private Boolean sandboxEnabled;

    /** 指派给的用户/团队/角色标识 */
    private String assignee;

    /** 指派类型：USER / TEAM / ROLE */
    private String assigneeType;

    /** 领取时间（用户实际接手任务的时间） */
    private LocalDateTime claimedAt;

    /** 截止时间 */
    private LocalDateTime dueAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}
