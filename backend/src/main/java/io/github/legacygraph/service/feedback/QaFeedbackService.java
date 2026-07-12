package io.github.legacygraph.service.feedback;

import io.github.legacygraph.dao.Neo4jWriteRepository;
import io.github.legacygraph.entity.QaFeedback;
import io.github.legacygraph.repository.QaFeedbackRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * G-09: QaFeedbackService — QA 反馈闭环服务。
 * <p>记录用户对 QA 回答的反馈（赞/踩/修正），用于：</p>
 * <ul>
 *   <li>PENDING_CONFIRM 边的评审推荐（L-08 联动）</li>
 *   <li>定位节点高亮（L-22 联动）</li>
 *   <li>Ragas 指标计算（G-10 联动）</li>
 * </ul>
 *
 * <p>S4-T2: 👍/👎 反馈写回 Neo4j 节点 {@code evidenceScore}，正反馈 +1 / 负反馈 -1（下限 -10），
 * 使负反馈节点在后续检索排序中下降。</p>
 */
@Slf4j
@Service
public class QaFeedbackService {

    private final QaFeedbackRepository qaFeedbackRepository;

    @Autowired(required = false)
    private Neo4jWriteRepository neo4jWriteRepository;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    /** 从 answer 中提取 [节点ID] 引用的正则（排除纯数字序号如 [1] [2]） */
    private static final Pattern NODE_REF_PATTERN = Pattern.compile("\\[([A-Za-z0-9_-]{8,})\\]");

    /** evidenceScore 下限，防止负反馈过度惩罚 */
    private static final int EVIDENCE_SCORE_FLOOR = -10;

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

        // S4-T2: 👍/👎 写回 evidenceScore
        backfillEvidenceScore(feedback);
        return feedback;
    }

    /**
     * S4-T2: 将反馈回写到引用节点的 evidenceScore。
     * <p>解析 usedEvidenceIds（JSON 数组）和 answer 中的 [节点ID] 引用，
     * 正反馈 +1，负反馈 -1（下限 {@value #EVIDENCE_SCORE_FLOOR}）。</p>
     */
    private void backfillEvidenceScore(QaFeedback feedback) {
        if (neo4jWriteRepository == null || feedback == null || feedback.getHelpful() == null) {
            return;
        }
        Set<String> nodeIds = extractCitedNodeIds(feedback);
        if (nodeIds.isEmpty()) {
            return;
        }
        int delta = Boolean.TRUE.equals(feedback.getHelpful()) ? 1 : -1;
        String cypher = "MATCH (n {id: $nodeId}) " +
                "SET n.evidenceScore = CASE " +
                "WHEN coalesce(n.evidenceScore, 0) + $delta < " + EVIDENCE_SCORE_FLOOR +
                " THEN " + EVIDENCE_SCORE_FLOOR + " " +
                "ELSE coalesce(n.evidenceScore, 0) + $delta END";
        int updated = 0;
        for (String nodeId : nodeIds) {
            try {
                neo4jWriteRepository.executeWriteQuery(cypher,
                        Map.of("nodeId", nodeId, "delta", delta));
                updated++;
            } catch (Exception e) {
                log.debug("S4-T2: evidenceScore backfill failed for node {}: {}", nodeId, e.getMessage());
            }
        }
        log.info("S4-T2: evidenceScore backfilled for {} nodes (delta={}, helpful={})",
                updated, delta, feedback.getHelpful());
    }

    /**
     * 提取反馈中引用的图谱节点 ID 集合。
     * 来源：① usedEvidenceIds JSON 数组 ② answer 中的 [节点ID] 引用（S4-T1 联动）
     */
    private Set<String> extractCitedNodeIds(QaFeedback feedback) {
        Set<String> nodeIds = new LinkedHashSet<>();
        // ① usedEvidenceIds JSON 数组
        if (feedback.getUsedEvidenceIds() != null && !feedback.getUsedEvidenceIds().isBlank()
                && objectMapper != null) {
            try {
                List<String> ids = objectMapper.readValue(
                        feedback.getUsedEvidenceIds(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                if (ids != null) {
                    nodeIds.addAll(ids);
                }
            } catch (Exception e) {
                log.debug("S4-T2: failed to parse usedEvidenceIds JSON: {}", e.getMessage());
            }
        }
        // ② answer 中的 [节点ID] 引用
        if (feedback.getAnswer() != null) {
            Matcher m = NODE_REF_PATTERN.matcher(feedback.getAnswer());
            while (m.find()) {
                nodeIds.add(m.group(1));
            }
        }
        return nodeIds;
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
