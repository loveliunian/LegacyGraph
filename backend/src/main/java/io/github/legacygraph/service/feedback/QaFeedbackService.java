package io.github.legacygraph.service.feedback;

import io.github.legacygraph.entity.QaFeedback;
import io.github.legacygraph.repository.QaFeedbackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * G-09: QaFeedbackService — QA 反馈闭环服务。
 * <p>记录用户对 QA 回答的反馈（赞/踩/修正），用于：</p>
 * <ul>
 *   <li>PENDING_CONFIRM 边的评审推荐（L-08 联动）</li>
 *   <li>定位节点高亮（L-22 联动）</li>
 *   <li>Ragas 指标计算（G-10 联动）</li>
 * </ul>
 */
@Slf4j
@Service
public class QaFeedbackService {

    private final QaFeedbackRepository qaFeedbackRepository;

    @Autowired
    public QaFeedbackService(QaFeedbackRepository qaFeedbackRepository) {
        this.qaFeedbackRepository = qaFeedbackRepository;
    }

    /**
     * 记录用户反馈。
     *
     * @param conversationId 对话 ID
     * @param messageId       消息 ID
     * @param projectId       项目 ID
     * @param helpful         是否有用（true=赞 / false=踩）
     * @param question        原始问题
     * @param answer          原始答案
     * @param feedbackText    用户修正/评论
     * @return 创建的反馈记录
     */
    public QaFeedback recordFeedback(String conversationId, String messageId, String projectId,
                                      Boolean helpful, String question, String answer, String feedbackText) {
        QaFeedback feedback = new QaFeedback();
        feedback.setConversationId(conversationId);
        feedback.setMessageId(messageId);
        feedback.setProjectId(projectId);
        feedback.setHelpful(helpful);
        feedback.setQuestion(question);
        feedback.setAnswer(answer);
        feedback.setFeedbackText(feedbackText);
        feedback.setCreatedAt(LocalDateTime.now());

        try {
            qaFeedbackRepository.insert(feedback);
            log.info("QA feedback recorded: conversationId={}, helpful={}", conversationId, helpful);
        } catch (Exception e) {
            log.warn("Failed to record QA feedback: {}", e.getMessage());
        }
        return feedback;
    }

    /**
     * 查询对话的反馈记录。
     */
    public List<QaFeedback> getFeedbackByConversation(String conversationId) {
        try {
            return qaFeedbackRepository.lambdaQuery()
                    .eq(QaFeedback::getConversationId, conversationId)
                    .orderByDesc(QaFeedback::getCreatedAt)
                    .list();
        } catch (Exception e) {
            log.warn("Failed to get feedback: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取负面反馈（helpful=false）列表，用于评审推荐。
     */
    public List<QaFeedback> getNegativeFeedback(int limit) {
        try {
            return qaFeedbackRepository.lambdaQuery()
                    .eq(QaFeedback::getHelpful, false)
                    .orderByDesc(QaFeedback::getCreatedAt)
                    .last("LIMIT " + Math.min(limit, 100))
                    .list();
        } catch (Exception e) {
            log.warn("Failed to get negative feedback: {}", e.getMessage());
            return List.of();
        }
    }
}
