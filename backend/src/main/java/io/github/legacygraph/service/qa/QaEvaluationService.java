package io.github.legacygraph.service.qa;

import io.github.legacygraph.dto.qa.QaEvaluationResult;
import io.github.legacygraph.entity.QaTestCase;

import java.util.List;

/**
 * QA 评测服务 — 运行评测集并计算质量指标，用于发布前 QA 门禁。
 * <p>
 * 指标：
 * <ul>
 *   <li>{@code entityRecall}：期望实体被答案引用的比例</li>
 *   <li>{@code evidencePrecision}：答案引用证据中有效的比例</li>
 *   <li>{@code requiredKeywordCoverage}：期望关键词被答案覆盖的比例</li>
 *   <li>{@code abstentionAccuracy}：拒答准确性（该拒答的拒答 + 该回答的回答）</li>
 * </ul>
 * 门禁阈值：entityRecall≥0.85、evidencePrecision≥0.90、abstentionAccuracy≥0.95，任一不满足即拦截发布。
 */
public interface QaEvaluationService {

    /** 拒答关键词（小写匹配） */
    List<String> ABSTENTION_PHRASES = List.of(
            "无法确定", "无法回答", "不知道", "图谱中没有", "图谱中未找到",
            "没有找到", "未找到", "没有相关", "暂无", "无法提供",
            "不在图谱", "未在图谱", "没有对应", "不明确");

    /**
     * 运行指定测试用例集合并返回评测结果。
     *
     * @param projectId  项目 ID
     * @param versionId  扫描版本 ID
     * @param testCases  测试用例列表
     * @return 评测结果（含各指标分数与每条用例记录）
     */
    QaEvaluationResult evaluate(String projectId, String versionId, List<QaTestCase> testCases);

    /**
     * 运行预定义的冒烟测试集（从数据库加载 status=SMOKE 的用例）。
     *
     * @param projectId 项目 ID
     * @param versionId 扫描版本 ID
     * @return 评测结果；无 SMOKE 用例时返回 passed=true 的空结果
     */
    QaEvaluationResult runSmoke(String projectId, String versionId);
}
