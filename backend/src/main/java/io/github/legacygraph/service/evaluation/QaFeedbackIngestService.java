package io.github.legacygraph.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.QaFeedbackEntity;
import io.github.legacygraph.entity.SolutionReviewDiff;
import io.github.legacygraph.repository.QaFeedbackEntityRepository;
import io.github.legacygraph.repository.SolutionReviewDiffRepository;
import io.github.legacygraph.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * QA 反馈接入服务 — 负责声明级反馈的持久化与方案评审差异的记录。
 *
 * <p>核心职责：
 * <ul>
 *   <li>接收对图谱声明（claim）的 QA 反馈并持久化，包含期望证据列表</li>
 *   <li>记录方案评审过程中每一步的修改差异，便于追溯</li>
 *   <li>提供按项目 / 方案维度的反馈查询能力</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaFeedbackIngestService {

    private final QaFeedbackEntityRepository qaFeedbackEntityRepository;
    private final SolutionReviewDiffRepository solutionReviewDiffRepository;
    private final ObjectMapper objectMapper;

    /**
     * 提交 QA 声明反馈。
     *
     * <p>将问题文本经 SHA-256 计算哈希后与反馈一并持久化，
     * 期望证据列表序列化为 JSON 字符串存储。</p>
     *
     * @param projectId        项目 ID
     * @param graphReleaseId   图谱发布版本 ID
     * @param question         原始问题文本（用于计算 question_hash）
     * @param claimText        被反馈的声明文本
     * @param feedbackType     反馈类型（如 MISSING_EVIDENCE / WRONG_CLAIM / HALLUCINATION）
     * @param expectedEvidence 期望证据列表
     * @param principal        触发反馈的主体（如 user:alice）
     * @return 已持久化的反馈实体
     */
    public QaFeedbackEntity ingest(String projectId, String graphReleaseId, String question,
                                   String claimText, String feedbackType,
                                   List<String> expectedEvidence, String principal) {
        QaFeedbackEntity entity = new QaFeedbackEntity();
        entity.setId(IdUtil.fastUUID());
        entity.setProjectId(projectId);
        entity.setGraphReleaseId(graphReleaseId);
        entity.setQuestionHash(sha256(question));
        entity.setClaimText(claimText);
        entity.setFeedbackType(feedbackType);
        entity.setExpectedEvidence(toJson(expectedEvidence));
        entity.setPrincipal(principal);
        entity.setCreatedAt(LocalDateTime.now());
        qaFeedbackEntityRepository.insert(entity);
        log.info("QA feedback ingested: projectId={}, releaseId={}, feedbackType={}, principal={}",
                projectId, graphReleaseId, feedbackType, principal);
        return entity;
    }

    /**
     * 查询项目下的全部 QA 反馈，按创建时间倒序。
     *
     * @param projectId 项目 ID
     * @return 反馈列表
     */
    public List<QaFeedbackEntity> findByProject(String projectId) {
        return qaFeedbackEntityRepository.lambdaQuery()
                .eq(QaFeedbackEntity::getProjectId, projectId)
                .orderByDesc(QaFeedbackEntity::getCreatedAt)
                .list();
    }

    /**
     * 记录方案评审差异。
     *
     * @param solutionId     方案 ID
     * @param reviewer       评审人
     * @param stepIndex      步骤索引
     * @param diffType       差异类型（如 MODIFIED / ADDED / REMOVED）
     * @param beforeSummary  修改前摘要
     * @param afterSummary   修改后摘要
     * @return 已持久化的评审差异实体
     */
    public SolutionReviewDiff recordReviewDiff(String solutionId, String reviewer, int stepIndex,
                                                String diffType, String beforeSummary,
                                                String afterSummary) {
        SolutionReviewDiff diff = new SolutionReviewDiff();
        diff.setId(IdUtil.fastUUID());
        diff.setSolutionId(solutionId);
        diff.setReviewer(reviewer);
        diff.setStepIndex(stepIndex);
        diff.setDiffType(diffType);
        diff.setBeforeSummary(beforeSummary);
        diff.setAfterSummary(afterSummary);
        diff.setCreatedAt(LocalDateTime.now());
        solutionReviewDiffRepository.insert(diff);
        log.info("Solution review diff recorded: solutionId={}, stepIndex={}, diffType={}, reviewer={}",
                solutionId, stepIndex, diffType, reviewer);
        return diff;
    }

    /**
     * 查询方案下的全部评审差异，按步骤索引正序。
     *
     * @param solutionId 方案 ID
     * @return 评审差异列表
     */
    public List<SolutionReviewDiff> findReviewDiffs(String solutionId) {
        return solutionReviewDiffRepository.lambdaQuery()
                .eq(SolutionReviewDiff::getSolutionId, solutionId)
                .orderByAsc(SolutionReviewDiff::getStepIndex)
                .list();
    }

    /**
     * 计算字符串的 SHA-256 哈希（十六进制），null 安全。
     */
    private String sha256(String input) {
        if (input == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to compute SHA-256: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将字符串列表序列化为 JSON，失败时返回 null。
     */
    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("Failed to serialize expectedEvidence: {}", e.getMessage());
            return null;
        }
    }
}
