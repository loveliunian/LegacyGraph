package io.github.legacygraph.dto.solution;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 输出的方案计划 DTO（Task 10）。
 * <p>由 {@code SolutionPlanner} 通过 solution-planning prompt 调用 LLM 得到，
 * 含方案总览与步骤列表，落库为 {@code lg_solution} + {@code lg_solution_step}。</p>
 */
@Data
@NoArgsConstructor
public class SolutionPlan {

    /** 方案一句话总览 */
    private String summary;

    /** 实施步骤列表（按 stepIndex 顺序） */
    private List<SolutionPlanStep> steps = new ArrayList<>();

    /** 成本估算（人天/复杂度/影响文件数） */
    private CostEstimate estimatedCost;

    /** 风险评估（高风险区域 + 缓解措施） */
    private RiskAssessment riskAssessment;

    /** 备选方案列表（最多 2 个） */
    private List<Alternative> alternatives = new ArrayList<>();

    /** 成本估算 */
    @Data
    @NoArgsConstructor
    public static class CostEstimate {
        /** 预估人天 */
        private double personDays;
        /** 影响文件数 */
        private int affectedFiles;
        /** 复杂度等级：LOW / MEDIUM / HIGH */
        private String complexity;
    }

    /** 风险评估 */
    @Data
    @NoArgsConstructor
    public static class RiskAssessment {
        /** 风险等级：LOW / MEDIUM / HIGH */
        private String riskLevel;
        /** 高风险区域描述列表 */
        private List<String> highRiskAreas = new ArrayList<>();
        /** 缓解措施列表 */
        private List<String> mitigations = new ArrayList<>();
    }

    /** 备选方案 */
    @Data
    @NoArgsConstructor
    public static class Alternative {
        /** 备选方案名称 */
        private String name;
        /** 方案简述 */
        private String description;
        /** 优劣对比 */
        private String tradeoffs;
    }
}
