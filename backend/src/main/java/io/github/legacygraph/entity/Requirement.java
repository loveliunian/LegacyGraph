package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 需求主表实体（Task 6）。
 * <p>承载一份需求文本及其 LLM 抽取的整体目标。</p>
 */
@Data
@TableName("lg_requirement")
public class Requirement {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    /** 需求原文 */
    private String text;

    /** LLM 抽取的总体目标 */
    private String goal;

    /** 状态：ANALYZED / FAILED / PENDING */
    private String status;

    private LocalDateTime createdAt;
}
