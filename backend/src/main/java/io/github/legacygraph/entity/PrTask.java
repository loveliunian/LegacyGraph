package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * PR 任务实体（增强版2：ChangeTask 管道）。
 * <p>
 * AI 只能创建 feature branch，未过门禁不能创建 PR。保留回滚计划与审核策略
 * （见设计文档 §安全与回滚策略）。
 * </p>
 */
@Data
@TableName("lg_pr_task")
public class PrTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String changeTaskId;

    private String branchName;

    private String prUrl;

    /** NOT_CREATED / DRAFT / CREATED / MERGED / CLOSED，默认 NOT_CREATED */
    private String prStatus;

    /** 审核策略（JSON：bugfix 1 人 / upgrade 2 人 / schema 变更需 DBA） */
    private String reviewerPolicy;

    /** 回滚计划（JSON：回滚脚本、回滚标签、环境镜像版本） */
    private String rollbackPlan;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
