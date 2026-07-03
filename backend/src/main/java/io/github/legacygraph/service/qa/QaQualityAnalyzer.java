package io.github.legacygraph.service.qa;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.QaFeedback;
import io.github.legacygraph.entity.QaMessage;
import io.github.legacygraph.repository.QaFeedbackRepository;
import io.github.legacygraph.repository.QaMessageRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * QA 质量分析服务 - 自动分析负反馈，识别问题模式。
 * <p>
 * 功能：
 * <ul>
 *   <li>定期扫描负反馈（helpful=false）</li>
 *   <li>按问题类型分类（无答案、答案错误、答案不完整等）</li>
 *   <li>生成质量报告</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaQualityAnalyzer {

    private final QaFeedbackRepository feedbackRepository;
    private final QaMessageRepository messageRepository;

    /**
     * 问题类型枚举
     */
    public enum IssueType {
        NO_ANSWER("无答案", "模型未返回有效回答"),
        WRONG_ANSWER("答案错误", "回答与事实不符"),
        INCOMPLETE_ANSWER("答案不完整", "回答遗漏关键信息"),
        IRRELEVANT_ANSWER("答非所问", "回答与问题不相关"),
        MISSING_EVIDENCE("缺少证据", "未引用相关代码或文档"),
        OTHER("其他", "其他问题");

        private final String label;
        private final String description;

        IssueType(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() { return label; }
        public String getDescription() { return description; }
    }

    /**
     * 质量分析报告
     */
    @Data
    public static class QualityReport {
        private LocalDateTime generatedAt;
        private int totalNegativeFeedback;
        private Map<IssueType, Integer> issueDistribution;
        private List<QaFeedback> recentNegativeFeedback;
        private double negativeRate; // 负反馈占总反馈比例
    }

    /**
     * 分析指定时间范围内的负反馈。
     *
     * @param since 起始时间
     * @return 质量分析报告
     */
    public QualityReport analyzeNegativeFeedback(LocalDateTime since) {
        log.info("开始分析负反馈，时间范围：{} 至今", since);

        LambdaQueryWrapper<QaFeedback> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QaFeedback::getHelpful, false)
               .ge(QaFeedback::getCreatedAt, since)
               .orderByDesc(QaFeedback::getCreatedAt);

        List<QaFeedback> negativeFeedbacks = feedbackRepository.selectList(wrapper);

        // 统计问题分布
        Map<IssueType, Integer> distribution = classifyIssues(negativeFeedbacks);

        // 计算负反馈率
        LambdaQueryWrapper<QaFeedback> allWrapper = new LambdaQueryWrapper<>();
        allWrapper.ge(QaFeedback::getCreatedAt, since);
        long totalFeedback = feedbackRepository.selectCount(allWrapper);
        double negativeRate = totalFeedback > 0
            ? (double) negativeFeedbacks.size() / totalFeedback
            : 0.0;

        QualityReport report = new QualityReport();
        report.setGeneratedAt(LocalDateTime.now());
        report.setTotalNegativeFeedback(negativeFeedbacks.size());
        report.setIssueDistribution(distribution);
        report.setRecentNegativeFeedback(negativeFeedbacks.stream().limit(20).collect(Collectors.toList()));
        report.setNegativeRate(negativeRate);

        log.info("负反馈分析完成：共 {} 条，负反馈率 {:.2%}", negativeFeedbacks.size(), negativeRate);
        return report;
    }

    /**
     * 对负反馈进行问题分类。
     * <p>
     * 分类逻辑：
     * <ul>
     *   <li>根据 feedbackText 关键词匹配</li>
     *   <li>根据 answer 长度判断是否为"无答案"</li>
     *   <li>根据 usedEvidenceIds 判断是否"缺少证据"</li>
     * </ul>
     */
    private Map<IssueType, Integer> classifyIssues(List<QaFeedback> feedbacks) {
        Map<IssueType, Integer> distribution = new EnumMap<>(IssueType.class);
        for (IssueType type : IssueType.values()) {
            distribution.put(type, 0);
        }

        for (QaFeedback fb : feedbacks) {
            IssueType type = classifySingleFeedback(fb);
            distribution.merge(type, 1, Integer::sum);
        }

        return distribution;
    }

    /**
     * 对单条负反馈进行分类。
     */
    private IssueType classifySingleFeedback(QaFeedback fb) {
        String feedbackText = fb.getFeedbackText() != null ? fb.getFeedbackText().toLowerCase() : "";
        String answer = fb.getAnswer() != null ? fb.getAnswer() : "";

        // 1. 无答案
        if (answer.isBlank() || answer.length() < 20) {
            return IssueType.NO_ANSWER;
        }

        // 2. 缺少证据
        if (fb.getUsedEvidenceIds() == null || fb.getUsedEvidenceIds().isBlank()) {
            if (feedbackText.contains("证据") || feedbackText.contains("引用") || feedbackText.contains("来源")) {
                return IssueType.MISSING_EVIDENCE;
            }
        }

        // 3. 答非所问
        if (feedbackText.contains("不相关") || feedbackText.contains("答非所问") || feedbackText.contains("无关")) {
            return IssueType.IRRELEVANT_ANSWER;
        }

        // 4. 答案错误
        if (feedbackText.contains("错误") || feedbackText.contains("不对") || feedbackText.contains("不准确")) {
            return IssueType.WRONG_ANSWER;
        }

        // 5. 答案不完整
        if (feedbackText.contains("不完整") || feedbackText.contains("缺少") || feedbackText.contains("遗漏")) {
            return IssueType.INCOMPLETE_ANSWER;
        }

        return IssueType.OTHER;
    }

    /**
     * 定时任务：每天凌晨 2 点自动分析过去 7 天的负反馈。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledAnalysis() {
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        QualityReport report = analyzeNegativeFeedback(weekAgo);

        log.info("=== 每周 QA 质量报告 ===");
        log.info("时间范围：{} 至 {}", weekAgo, LocalDateTime.now());
        log.info("负反馈总数：{}", report.getTotalNegativeFeedback());
        log.info("负反馈率：{:.2%}", report.getNegativeRate());
        log.info("问题分布：");
        report.getIssueDistribution().forEach((type, count) ->
            log.info("  - {} ({}): {} 条", type.getLabel(), type.getDescription(), count)
        );
    }

    /**
     * 获取最常见的 N 个问题类型。
     */
    public List<Map.Entry<IssueType, Integer>> getTopIssues(int topN, LocalDateTime since) {
        QualityReport report = analyzeNegativeFeedback(since);
        return report.getIssueDistribution().entrySet().stream()
            .sorted(Map.Entry.<IssueType, Integer>comparingByValue().reversed())
            .limit(topN)
            .collect(Collectors.toList());
    }
}
