package io.github.legacygraph.dto.requirement;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 需求条目增量更新请求（G-22）。
 * <p>所有字段均为可选，仅更新非 null 字段。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItemPatchRequest {

    /** 条目描述（可选） */
    private String text;

    /** 约束列表（可选） */
    private List<String> constraints;

    /** 验收条件列表（可选，非空时替换该条目的旧 AC） */
    private List<String> acceptanceCriteria;
}
