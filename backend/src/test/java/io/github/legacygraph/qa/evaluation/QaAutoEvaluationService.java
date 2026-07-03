package io.github.legacygraph.qa.evaluation;

import io.github.legacygraph.entity.QaMessage;
import io.github.legacygraph.repository.QaMessageRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * QA 自动评估服务 - 定期评估历史问答质量
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaAutoEvaluationService {

    private final QaMessageRepository messageRepository;
    private final QaEvaluationService evaluationService;

    /**
     * 自动评估指标
     */
    @Data
    public static class AutoEvaluationMetrics {
        private LocalDateTime evaluatedAt;
        private int totalQuestions;           // 总问题数
        private int answeredQuestions;        // 已回答数
        private double answerRate;            // 回答率
        private double avgAnswerLength;       // 平均回答长度
        private double avgResponseTime;       // 平均响应时间
        private Map<String, Integer> categoryDistribution; // 问题类别分布
    }

    /**
     * 评估指定时间范围内的问答质量
     */
    public AutoEvaluationMetrics evaluate(LocalDateTime since) {
        log.info("开始自动评估，时间范围：{} 至今", since);

        LambdaQueryWrapper<QaMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QaMessage::getRole, "USER")
               .ge(QaMessage::getCreatedAt, since);

        List<QaMessage> userMessages = messageRepository.selectList(wrapper);
        
        AutoEvaluationMetrics metrics = new AutoEvaluationMetrics();
        metrics.setEvaluatedAt(LocalDateTime.now());
        metrics.setTotalQuestions(userMessages.size());

        // 统计已回答的问题
        long answeredCount = userMessages.stream()
            .filter(msg -> hasAssistantResponse(msg.getConversationId()))
            .count();
        metrics.setAnsweredQuestions((int) answeredCount);
        metrics.setAnswerRate(answeredCount > 0 ? (double) answeredCount / userMessages.size() : 0);

        // 统计平均回答长度
        List<QaMessage> assistantMessages = messageRepository.selectList(
            new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getRole, "ASSISTANT")
                .ge(QaMessage::getCreatedAt, since)
        );
        
        if (!assistantMessages.isEmpty()) {
            double avgLength = assistantMessages.stream()
                .mapToInt(msg -> msg.getContent() != null ? msg.getContent().length() : 0)
                .average()
                .orElse(0);
            metrics.setAvgAnswerLength(avgLength);
        }

        log.info("自动评估完成：共 {} 个问题，回答率 {:.2%}", 
            metrics.getTotalQuestions(), metrics.getAnswerRate());
        
        return metrics;
    }

    /**
     * 检查对话是否有助手回复
     */
    private boolean hasAssistantResponse(String conversationId) {
        LambdaQueryWrapper<QaMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QaMessage::getConversationId, conversationId)
               .eq(QaMessage::getRole, "ASSISTANT");
        return messageRepository.selectCount(wrapper) > 0;
    }

    /**
     * 定时任务：每周一凌晨 3 点自动评估过去 7 天的问答质量
     */
    @Scheduled(cron = "0 0 3 ? * MON")
    public void weeklyEvaluation() {
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        AutoEvaluationMetrics metrics = evaluate(weekAgo);

        log.info("========== 每周 QA 自动评估报告 ==========");
        log.info("时间范围：{} 至 {}", weekAgo, LocalDateTime.now());
        log.info("问题总数：{}", metrics.getTotalQuestions());
        log.info("回答率：{:.2%}", metrics.getAnswerRate());
        log.info("平均回答长度：{:.0f} 字符", metrics.getAvgAnswerLength());
        log.info("==========================================");
    }

    /**
     * 回放历史问题并重新评估
     */
    public List<QaEvaluationResult> replayAndEvaluate(LocalDateTime since) {
        log.info("开始回放历史问题并重新评估，时间范围：{} 至今", since);

        LambdaQueryWrapper<QaMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QaMessage::getRole, "USER")
               .ge(QaMessage::getCreatedAt, since)
               .orderByDesc(QaMessage::getCreatedAt)
               .last("LIMIT 50"); // 限制回放数量

        List<QaMessage> userMessages = messageRepository.selectList(wrapper);
        
        // 转换为评测用例
        List<io.github.legacygraph.qa.evaluation.QaTestCase> testCases = userMessages.stream()
            .map(msg -> {
                io.github.legacygraph.qa.evaluation.QaTestCase tc = new io.github.legacygraph.qa.evaluation.QaTestCase();
                tc.setId(msg.getId());
                tc.setQuestion(msg.getContent());
                tc.setContext(Map.of("conversationId", msg.getConversationId()));
                return tc;
            })
            .collect(Collectors.toList());

        log.info("回放了 {} 个历史问题", testCases.size());
        
        // TODO: 调用 evaluationService 重新评估
        // return evaluationService.runEvaluation(testCases);
        return Collections.emptyList();
    }
}
