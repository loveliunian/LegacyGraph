package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 补丁文件实体（增强版2：ChangeTask 管道）。
 * <p>
 * 一个 ChangeTask 可产出多个 PatchFile。补丁以 unified diff 形式保存，
 * 落盘前必须通过范围校验、格式校验、证据校验（见设计文档 §PatchPlan 输出契约）。
 * </p>
 */
@Data
@TableName("lg_patch_file")
public class PatchFile {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String changeTaskId;

    private String filePath;

    /** CREATE / MODIFY / DELETE */
    private String changeType;

    private String beforeSha;

    private String afterSha;

    /** unified diff 文本 */
    private String patchText;

    /** 生成者（Agent 名或人工） */
    private String generatedBy;

    /** 引用的证据ID列表（JSON 数组），无证据不得生成 PR */
    private String evidenceIds;

    /** DRAFT / APPLIED / REVIEW_PENDING / DISCARDED，默认 DRAFT */
    private String status;

    private LocalDateTime createdAt;

    private Integer deleted;
}
