package io.github.legacygraph.dto.requirement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 风险因子 DTO（G-06）。
 * <p>封装影响分析中各维度风险权重，最终风险分数 = 各因子乘积。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskFactor {

    /** 关系置信度（0~1，推断边取 0.7，显式边取 1.0） */
    private double relationConfidence;

    /** 路径衰减因子（随深度递减，1/log2(depth+2)） */
    private double pathDecay;

    /** 变更类型权重（SchemaChange=1.5, ApiContract=1.4, InternalOnly=1.0, ReadOnly=0.6） */
    private double changeTypeWeight;

    /** 关键资产权重（核心节点加权，默认 1.0） */
    private double criticalAssetWeight;

    /** 缺少测试惩罚（有测试=1.0，无测试=1.5） */
    private double missingTestPenalty;

    /** 运行时热度权重（高频调用节点加权，默认 1.0） */
    private double runtimeHotness;

    /**
     * 计算综合风险分数。
     * <p>公式：risk = relationConfidence × pathDecay × changeTypeWeight
     * × criticalAssetWeight × missingTestPenalty × runtimeHotness</p>
     *
     * @return 风险分数（越大表示风险越高）
     */
    public double calculate() {
        return relationConfidence * pathDecay * changeTypeWeight
                * criticalAssetWeight * missingTestPenalty * runtimeHotness;
    }
}
