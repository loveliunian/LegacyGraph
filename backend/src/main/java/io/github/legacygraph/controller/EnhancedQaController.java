package io.github.legacygraph.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.EnhancedQaAgent;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.QaConversation;
import io.github.legacygraph.entity.QaFeedback;
import io.github.legacygraph.entity.QaMessage;
import io.github.legacygraph.repository.QaFeedbackRepository;
import io.github.legacygraph.service.qa.ConversationContextManager;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import io.github.legacygraph.util.IdUtil;

/**
 * QA 问答控制器 - 支持流式输出和多轮对话
 */
@Slf4j
@RestController
@RequestMapping("/qa")
@RequiredArgsConstructor
public class EnhancedQaController {

    private final EnhancedQaAgent enhancedQaAgent;
    private final ConversationContextManager conversationManager;
    private final QaFeedbackRepository feedbackRepository;
    private final ObjectMapper objectMapper;
    private final java.util.concurrent.Executor taskExecutor;

    /**
     * 流式问答接口
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody QaStreamRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        
        // 校验必填参数
        if (request.getProjectId() == null || request.getProjectId().isBlank()) {
            try {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                    .name("error")
                    .data("{\"message\":\"projectId 不能为空\"}"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        
        // 异步执行（使用受控 TaskExecutor，替代裸线程）
        taskExecutor.execute(() -> enhancedQaAgent.answerStream(
            request.getProjectId(),
            request.getVersionId(),
            request.getQuestion(),
            request.getConversationId(),
            emitter
        ));
        
        return emitter;
    }

    /**
     * 列出对话历史
     */
    @GetMapping("/conversations")
    public Result<List<QaConversation>> listConversations(@RequestParam String projectId) {
        List<QaConversation> conversations = conversationManager.listConversations(projectId);
        return Result.success(conversations);
    }

    /**
     * 获取对话消息列表
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public Result<List<QaMessage>> getMessages(@PathVariable String conversationId) {
        List<QaMessage> messages = conversationManager.getConversationHistory(conversationId);
        return Result.success(messages);
    }

    /**
     * 删除对话
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> deleteConversation(@PathVariable String conversationId) {
        conversationManager.deleteConversation(conversationId);
        return Result.success();
    }

    /**
     * 提交反馈
     */
    @PostMapping("/feedback")
    public Result<Void> submitFeedback(@RequestBody FeedbackRequest request) {
        QaFeedback feedback = new QaFeedback();
        feedback.setId(IdUtil.fastUUID());
        feedback.setMessageId(request.getMessageId());
        feedback.setConversationId(request.getConversationId());
        feedback.setProjectId(request.getProjectId());
        feedback.setHelpful(request.getHelpful());
        feedback.setFeedbackText(request.getFeedbackText());
        feedback.setUsedEvidenceIds(toJson(request.getUsedEvidenceIds()));
        feedback.setQuestion(request.getQuestion());
        feedback.setAnswer(request.getAnswer());
        
        feedbackRepository.save(feedback);
        
        log.info("Feedback submitted: messageId={}, helpful={}", 
            request.getMessageId(), request.getHelpful());
        
        return Result.success();
    }

    private String toJson(List<String> value) {
        if (value == null || value.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize feedback evidence ids: {}", e.getMessage());
            return "[]";
        }
    }

    @Data
    public static class QaStreamRequest {
        private String projectId;
        private String versionId;
        private String question;
        private String conversationId;
    }

    @Data
    public static class FeedbackRequest {
        private String messageId;
        private String conversationId;
        private String projectId;
        private Boolean helpful;
        private String feedbackText;
        private List<String> usedEvidenceIds;
        private String question;
        private String answer;
    }
}
