package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 验收条件实体（Task 6）。
 */
@Data
@TableName("lg_acceptance_criterion")
public class AcceptanceCriterion {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String requirementItemId;

    /** 验收条件描述 */
    private String text;

    private LocalDateTime createdAt;
}
