package io.github.legacygraph.dto.change;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 变更任务补丁提案（G-12）。
 * <p>
 * 方案文档要求的 JSON 结构，承载从 SolutionStep.codeSnippet 序列化而来的
 * unified diff 集合。每个 {@link ProposalFile} 对应一个文件级变更步骤，
 * 包含文件路径、操作类型、符号名、unified diff、证据 ID、测试与回滚描述。
 * </p>
 * <p>与 {@link io.github.legacygraph.dto.graph.PatchPlan} 的区别：
 * PatchPlan 由 Agent 生成；ChangeTaskProposal 由方案桥接阶段从 SolutionStep 生成，
 * 作为后续补丁生成流程的起点（加速 Agent 生成过程）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeTaskProposal {

    /** 文件级变更列表 */
    private List<ProposalFile> files;

    /**
     * 单个文件级变更提案。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProposalFile {

        /** 目标文件路径 */
        private String filePath;

        /** 操作类型：CREATE / MODIFY / DELETE */
        private String op;

        /** 关联的符号名（类/方法/字段等） */
        private String symbolName;

        /** unified diff 文本 */
        private String diff;

        /** 支撑该变更的证据 ID 列表 */
        private List<String> evidenceIds;

        /** 测试描述（如何验证该变更） */
        private String testDescription;

        /** 回滚描述（如何回滚该变更） */
        private String rollbackDescription;
    }
}
