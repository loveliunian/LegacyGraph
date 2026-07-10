package io.github.legacygraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 需求结构化抽取结果（Task 6）。
 * <p>LLM 对需求文本结构化抽取后的整体输出，对应 requirement-analysis prompt。</p>
 */
@Data
public class RequirementAnalysis {

    /** 整份需求的总体目标 */
    private String goal;

    /** 拆分后的需求条目列表 */
    private List<RequirementItemDTO> items = new ArrayList<>();

    /** 信息缺失/模糊需确认的开放问题 */
    private List<String> openQuestions = new ArrayList<>();
}
