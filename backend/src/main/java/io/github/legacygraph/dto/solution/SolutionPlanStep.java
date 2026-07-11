package io.github.legacygraph.dto.solution;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 方案计划中的单个步骤（Task 10）。
 * <p>LLM 输出的文件级实施步骤原始结构，落库时转为 {@code SolutionStep} 实体。</p>
 */
@Data
@NoArgsConstructor
public class SolutionPlanStep {

    /** 步骤标题 */
    private String title;

    /** 步骤描述 */
    private String description;

    /** 目标文件路径 */
    private String filePath;

    /** 关联的符号名（类/方法/字段等） */
    private String symbolName;

    /** 证据 ID 列表（指向图谱中的 Evidence 节点） */
    private List<String> evidenceIds = new ArrayList<>();

    /**
     * 动作类型：CREATE / MODIFY / DELETE
     */
    private String actionType;

    /** 测试描述 */
    private String testDescription;

    /** 回滚描述 */
    private String rollbackDescription;

    /** 代码片段（MODIFY 时为修改后的方法/类代码，CREATE 时为新代码，DELETE 时为空） */
    private String codeSnippet;

    /** 代码语言（java/xml/sql/vue/ts 等） */
    private String codeLanguage;
}
