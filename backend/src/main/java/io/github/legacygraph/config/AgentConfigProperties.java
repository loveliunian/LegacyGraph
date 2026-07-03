package io.github.legacygraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
        /** 结构评分权重（默认 0.25） */
        private double scoreStructWeight = 0.25;
        /** 证据评分权重（默认 0.20） */
        private double scoreEvidenceWeight = 0.20;
        /** 运行时评分权重（默认 0.10） */
        private double scoreRuntimeWeight = 0.10;
        /** 历史评分权重（默认 0.10） */
        private double scoreHistoryWeight = 0.10;

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
}
