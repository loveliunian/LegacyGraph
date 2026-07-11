package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 需求条目实体（Task 6）。
 * <p>单条需求（如 R1/R2），挂验收条件与约束。</p>
 */
@Data
@TableName("lg_requirement_item")
public class RequirementItem {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String requirementId;

    /** 条目编码，如 R1、R2 */
    private String code;

    /** 条目描述 */
    private String text;

    /** 约束列表 JSON 数组字符串（V67：持久化 constraints，避免重建丢失） */
    private String constraintsJson;

    private LocalDateTime createdAt;
}
