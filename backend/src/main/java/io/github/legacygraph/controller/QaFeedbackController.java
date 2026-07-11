package io.github.legacygraph.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.qa.QaFeedbackRequest;
import io.github.legacygraph.entity.QaFeedback;
import io.github.legacygraph.repository.QaFeedbackRepository;
import io.github.legacygraph.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * QA 反馈控制器 — 通用反馈提交入口（非项目级路径）。
 * <p>
 * 与 {@link QaEvaluationController#submitFeedback} 不同，本控制器挂在全局
 * {@code /lg/qa/feedback} 路径下，适用于无法从 URL 中解析 projectId 的
 * 独立反馈场景（如答案卡片内嵌反馈按钮）。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/qa/feedback")
@RequiredArgsConstructor
public class QaFeedbackController {

    private final QaFeedbackRepository qaFeedbackRepository;
    private final ObjectMapper objectMapper;

    /**
     * 提交反馈。
     * <p>
     * 接收 {@link QaFeedbackRequest}，映射为 {@link QaFeedback} 实体持久化。
     * </p>
     *
     * @param request 反馈请求
     * @return 成功结果
     */
    @PostMapping
    public Result<Void> submitFeedback(@RequestBody QaFeedbackRequest request) {
        QaFeedback feedback = toEntity(request);
        qaFeedbackRepository.insert(feedback);
        log.info("QaFeedbackController: feedback submitted, answerId={}, feedbackType={}",
                request.getAnswerId(), request.getFeedbackType());
        return Result.success();
    }

    // ==================== 辅助方法 ====================

    /** 把反馈 DTO 映射为 QaFeedback 实体 */
    private QaFeedback toEntity(QaFeedbackRequest request) {
        QaFeedback feedback = new QaFeedback();
        feedback.setId(IdUtil.fastUUID());
        feedback.setMessageId(request.getAnswerId());
        feedback.setQuestion(request.getQuestion());
        feedback.setAnswer(request.getClaimText());
        feedback.setHelpful(toHelpful(request.getFeedbackType()));
        feedback.setFeedbackText("feedbackType=" + request.getFeedbackType());
        feedback.setUsedEvidenceIds(toJson(request.getExpectedEvidenceIds()));
        return feedback;
    }

    /** feedbackType → helpful 映射：POSITIVE 为 true，NEGATIVE 为 false，其他为 null */
    private Boolean toHelpful(String feedbackType) {
        if (feedbackType == null) {
            return null;
        }
        switch (feedbackType.toUpperCase()) {
            case "POSITIVE":
            case "THUMBS_UP":
            case "USEFUL":
                return Boolean.TRUE;
            case "NEGATIVE":
            case "THUMBS_DOWN":
            case "NOT_USEFUL":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /** 证据 ID 列表序列化为 JSON 字符串 */
    private String toJson(List<String> value) {
        if (value == null || value.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("QaFeedbackController: failed to serialize evidence ids: {}", e.getMessage());
            return "[]";
        }
    }
}
