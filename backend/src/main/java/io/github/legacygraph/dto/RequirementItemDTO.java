package io.github.legacygraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 需求条目 DTO（Task 6）。
 * <p>单条需求的结构化表示，含验收条件与约束。</p>
 */
@Data
public class RequirementItemDTO {

    /** 条目编码，如 R1、R2 */
    private String code;

    /** 需求条目描述 */
    private String text;

    /** 验收条件列表（至少一条） */
    private List<String> acceptanceCriteria = new ArrayList<>();

    /** 约束列表 */
    private List<String> constraints = new ArrayList<>();
}
