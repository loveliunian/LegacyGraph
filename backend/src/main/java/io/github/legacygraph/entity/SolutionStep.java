package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 方案步骤实体（Task 10）。
 * <p>方案的单个文件级实施步骤，含文件路径、符号、动作类型、测试与回滚描述，
 * 以及支撑该步骤的证据 ID 列表（JSON 数组字符串）。</p>
 */
@Data
@TableName("lg_solution_step")
public class SolutionStep {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属方案 ID */
    private String solutionId;

    /** 步骤序号（从 0 开始） */
    private Integer stepIndex;

    /** 步骤标题 */
    private String title;

    /** 步骤描述（具体实施内容） */
    private String description;

    /** 步骤目标文件路径 */
    private String filePath;

    /** 步骤关联的符号名（类/方法/字段等） */
    private String symbolName;

    /** 证据 ID 列表（JSON 数组字符串，如 ["ev1","ev2"]） */
    private String evidenceIds;

    /**
     * 动作类型：CREATE / MODIFY / DELETE
     */
    private String actionType;

    /** 测试描述（如何验证该步骤） */
    private String testDescription;

    /** 回滚描述（如何回滚该步骤） */
    private String rollbackDescription;

    /** 代码片段（MODIFY 为修改后代码，CREATE 为新代码，DELETE 为空） */
    private String codeSnippet;

    /** 代码语言（java/xml/sql/vue/ts 等） */
    private String codeLanguage;

    /** 变更范围：FULL（完整替换）/ PARTIAL（部分修改） */
    private String changeScope;

    /** 需新增/修改的测试文件列表（JSON 数组字符串） */
    private String testFiles;

    private LocalDateTime createdAt;
}
