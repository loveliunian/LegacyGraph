package io.github.legacygraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 相关配置属性 —— 从 application.yml 读取 legacygraph.agent 配置。
 * <p>
 * 涵盖：图谱合并决策阈值、多证据评分权重、置信度计算权重。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "legacygraph.agent")
public class AgentConfigProperties {

    /** 图谱合并相关配置 */
    private MergeConfig merge = new MergeConfig();

    @Data
    public static class MergeConfig {
        // ===== LLM 合并决策阈值（传给 GraphMergeAgent.decideMerge） =====
        /** 名称相似度权重（默认 0.8） */
        private double nameWeight = 0.8;
        /** 语义相似度权重（默认 0.7） */
        private double semanticWeight = 0.7;
        /** 结构相似度权重（默认 0.6） */
        private double structWeight = 0.6;
        /** 邻居相似度权重（默认 0.5） */
        private double neighborWeight = 0.5;
        /** 证据相似度权重（默认 0.7） */
        private double evidenceWeight = 0.7;

        // ===== 多证据评分权重（GraphMergeService.scoreCandidate） =====
        /** 名称评分权重（默认 0.35） */
        private double scoreNameWeight = 0.35;
        /** 语义评分权重（默认 0.0，业务域类型由 scoreWeightsByType 覆盖） */
        private double scoreSemanticWeight = 0.0;
        /** 结构评分权重（默认 0.25） */
        private double scoreStructWeight = 0.25;
        /** 证据评分权重（默认 0.20） */
        private double scoreEvidenceWeight = 0.20;
        /** 运行时评分权重（默认 0.10） */
        private double scoreRuntimeWeight = 0.10;
        /** 历史评分权重（默认 0.10） */
        private double scoreHistoryWeight = 0.10;

        // ===== 类型感知评分权重（改进④：按节点类型分桶） =====
        /**
         * 按节点类型分桶的评分权重。key = nodeType（如 "BusinessDomain"），value = 权重配置。
         * <p>未配置的节点类型使用 default 权重（即上述全局 score*Weight 字段）。</p>
         * <p>示例配置见 application.yml / graph-merge-optimization-plan.md 改进④。</p>
         */
        private Map<String, ScoreWeights> scoreWeightsByType = new HashMap<>();

        /**
         * 获取指定节点类型的评分权重，未配置则返回 null（调用方使用全局默认权重）。
         */
        public ScoreWeights weightsFor(String nodeType) {
            return nodeType != null ? scoreWeightsByType.get(nodeType) : null;
        }

        // ===== 自动决策阈值（GraphMergeService.decideMerge） =====
        /** 自动合并阈值（默认 0.85） */
        private double autoMergeThreshold = 0.85;
        /** 人工审核阈值（默认 0.50） */
        private double reviewThreshold = 0.50;

        // ===== 置信度计算权重（GraphMergeAgent.calculateFinalConfidence） =====
        /** 支持度权重 */
        private double confidenceSupportWeight = 0.50;
        /** 语义分权重 */
        private double confidenceSemanticWeight = 0.15;
        /** 结构分权重 */
        private double confidenceStructWeight = 0.15;
        /** 邻居分权重 */
        private double confidenceNeighborWeight = 0.10;
        /** 运行时验证权重 */
        private double confidenceRuntimeWeight = 0.05;
        /** 人工审核权重 */
        private double confidenceHumanWeight = 0.05;
        /** 冲突扣分系数 */
        private double confidenceConflictPenalty = 0.35;
    }

    /**
     * 类型感知的评分权重（改进④）。
     * <p>每个维度权重 0-1，六维之和应为 1.0（业务域类型语义分权重更高）。</p>
     */
    @Data
    public static class ScoreWeights {
        /** 名称相似度权重 */
        private double name = 0.35;
        /** 语义相似度权重 */
        private double semantic = 0.0;
        /** 结构邻域相似度权重 */
        private double struct = 0.25;
        /** 共享证据权重 */
        private double evidence = 0.20;
        /** 运行时共现权重 */
        private double runtime = 0.10;
        /** 历史权重 */
        private double history = 0.10;
    }
}
