package io.github.legacygraph.dto.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Ragas 指标报告 — RAG 系统质量评估结果。
 * <p>
 * 包含四个核心 Ragas 指标及附加详情：
 * <ul>
 *   <li>{@code contextPrecision}：上下文精确度（0~1），期望实体在检索上下文中的命中率</li>
 *   <li>{@code contextRecall}：上下文召回率（0~1），检索上下文对期望实体的覆盖率</li>
 *   <li>{@code faithfulness}：答案忠实度（0~1），答案句子在检索上下文中有依据的比例</li>
 *   <li>{@code answerRelevancy}：答案相关性（0~1），答案与问题关键词的重合度</li>
 * </ul>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagasReport {

    /** 上下文精确度（0~1）：期望实体在检索上下文中的命中率 */
    private double contextPrecision;

    /** 上下文召回率（0~1）：检索上下文对期望实体的覆盖率 */
    private double contextRecall;

    /** 答案忠实度（0~1）：答案句子在检索上下文中有依据的比例 */
    private double faithfulness;

    /** 答案相关性（0~1）：答案与问题关键词的重合度 */
    private double answerRelevancy;

    /** 附加信息（如各指标计算细节） */
    private Map<String, String> details;
}
