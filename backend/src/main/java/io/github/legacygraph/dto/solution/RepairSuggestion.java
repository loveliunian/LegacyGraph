package io.github.legacygraph.dto.solution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 方案修复建议 DTO（G-23）。
 * <p>当方案校验返回 NEEDS_INPUT 时，由 {@code SolutionRepairAdvisor} 根据错误类型
 * 生成对应的智能修复建议，帮助用户快速定位并修正方案问题。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepairSuggestion {

    /** 错误类型（对应校验错误码，如 FILE_NOT_FOUND / SYMBOL_NOT_FOUND 等） */
    private String errorType;

    /** 错误详情（原始错误信息摘要） */
    private String errorDetail;

    /** 修复建议文本 */
    private String suggestion;

    /** 建议的动作类型：CREATE / MODIFY / DELETE */
    private String actionType;

    /** 目标字段（需要修改的方案字段，如 filePath / symbolName / codeSnippet / steps） */
    private String targetField;
}
