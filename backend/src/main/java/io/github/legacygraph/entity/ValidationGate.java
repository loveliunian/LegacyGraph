package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 验证门禁实体（增强版2：ChangeTask 管道）。
 * <p>
 * 每个 ChangeTask 在生成 PR 前需通过一组门禁。门禁执行复用现有
 * TestExecutionScheduler / GraphValidatorService，不另造测试结果表
 * （见设计文档 §验证门禁与图谱回写）。
 * </p>
 */
@Data
@TableName("lg_validation_gate")
public class ValidationGate {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String changeTaskId;

    /** STATIC / UNIT / API / DB / E2E / MIGRATION */
    private String gateType;

    /** 执行命令 */
    private String command;

    /** PENDING / PASSED / FAILED，默认 PENDING */
    private String result;

    private String reportUri;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Integer deleted;
}
