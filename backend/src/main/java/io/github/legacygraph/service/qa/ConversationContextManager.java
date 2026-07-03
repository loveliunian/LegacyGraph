package io.github.legacygraph.service.qa;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.QaConversation;
import io.github.legacygraph.entity.QaMessage;
import io.github.legacygraph.repository.QaConversationRepository;
import io.github.legacygraph.repository.QaMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 对话上下文管理器 - 管理多轮对话历史
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationContextManager {

    private final QaConversationRepository conversationRepository;
    private final QaMessageRepository messageRepository;

    private static final int MAX_CONTEXT_MESSAGES = 10;
    private static final int RECENT_THRESHOLD = 4;

    /**
     * 获取或创建对话会话
     */
    public QaConversation getOrCreateConversation(String projectId, String sessionId, String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            QaConversation existing = conversationRepository.selectById(conversationId);
            if (existing != null && existing.getProjectId().equals(projectId)) {
                return existing;
            }
        }

        // 创建新会话
        QaConversation conversation = new QaConversation();
        conversation.setId(UUID.randomUUID().toString());
        conversation.setProjectId(projectId);
        conversation.setSessionId(sessionId);
        conversation.setTitle("新对话");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
        
        log.info("Created new conversation: id={}, projectId={}", conversation.getId(), projectId);
        return conversation;
    }

    /**
     * 保存用户消息
     */
    public QaMessage saveUserMessage(String conversationId, String content) {
        return saveMessage(conversationId, "USER", content, null, null);
    }

    /**
     * 保存助手消息
     */
    public QaMessage saveAssistantMessage(String conversationId, String content, 
                                          String evidencesJson, Double confidence) {
        return saveMessage(conversationId, "ASSISTANT", content, evidencesJson, confidence);
    }

    private QaMessage saveMessage(String conversationId, String role, String content,
                                  String evidencesJson, Double confidence) {
        QaMessage message = new QaMessage();
        message.setId(UUID.randomUUID().toString());
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setEvidences(evidencesJson);
        message.setConfidence(confidence != null ? 
            java.math.BigDecimal.valueOf(confidence) : null);
        message.setCreatedAt(LocalDateTime.now());
        messageRepository.save(message);

        // 更新会话的 updated_at
        QaConversation conversation = conversationRepository.selectById(conversationId);
        if (conversation != null) {
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationRepository.updateById(conversation);
        }

        return message;
    }

    /**
     * 获取对话历史（用于构建上下文）
     * 返回最近 MAX_CONTEXT_MESSAGES 条消息。
     */
    public List<QaMessage> getConversationHistory(String conversationId) {
        LambdaQueryWrapper<QaMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QaMessage::getConversationId, conversationId)
               .orderByAsc(QaMessage::getCreatedAt)
               .last("LIMIT " + MAX_CONTEXT_MESSAGES);
        return messageRepository.selectList(wrapper);
    }

    /**
     * 构建对话上下文文本（供 LLM 使用）。
     * <p>
     * 策略：
     * <ul>
     *   <li>早期消息（超过 RECENT_THRESHOLD 条）压缩为摘要</li>
     *   <li>最近消息保留原文</li>
     * </ul>
     */
    public String buildContextText(String conversationId) {
        List<QaMessage> history = getConversationHistory(conversationId);
        if (history.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("## 对话历史\n");

        // 如果消息数量超过阈值，对早期消息做摘要压缩
        int recentStart = Math.max(0, history.size() - RECENT_THRESHOLD);
        if (recentStart > 0) {
            // 早期消息摘要
            context.append("[早期对话摘要] ");
            StringBuilder earlySummary = new StringBuilder();
            for (int i = 0; i < recentStart; i++) {
                QaMessage msg = history.get(i);
                String roleLabel = "USER".equals(msg.getRole()) ? "用户" : "助手";
                // 只取每条消息的前 100 字符
                String content = msg.getContent();
                if (content != null && content.length() > 100) {
                    content = content.substring(0, 100) + "...";
                }
                earlySummary.append(roleLabel).append(": ").append(content).append(" ");
            }
            // 限制摘要总长度
            String summary = earlySummary.toString();
            if (summary.length() > 500) {
                summary = summary.substring(0, 500) + "...";
            }
            context.append(summary).append("\n");
        }

        // 最近消息保留原文
        for (int i = recentStart; i < history.size(); i++) {
            QaMessage msg = history.get(i);
            String roleLabel = "USER".equals(msg.getRole()) ? "用户" : "助手";
            context.append(roleLabel).append(": ").append(msg.getContent()).append("\n");
        }
        
        return context.toString();
    }

    /**
     * 更新会话标题（从第一条用户消息提取）
     */
    public void updateConversationTitle(String conversationId) {
        LambdaQueryWrapper<QaMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QaMessage::getConversationId, conversationId)
               .eq(QaMessage::getRole, "USER")
               .orderByAsc(QaMessage::getCreatedAt)
               .last("LIMIT 1");
        
        QaMessage firstUserMsg = messageRepository.selectOne(wrapper);
        if (firstUserMsg != null) {
            String title = firstUserMsg.getContent();
            if (title.length() > 50) {
                title = title.substring(0, 50) + "...";
            }
            
            QaConversation conversation = conversationRepository.selectById(conversationId);
            if (conversation != null) {
                conversation.setTitle(title);
                conversationRepository.updateById(conversation);
            }
        }
    }

    /**
     * 列出项目的所有对话
     */
    public List<QaConversation> listConversations(String projectId) {
        LambdaQueryWrapper<QaConversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QaConversation::getProjectId, projectId)
               .orderByDesc(QaConversation::getUpdatedAt);
        List<QaConversation> conversations = conversationRepository.selectList(wrapper);
        for (QaConversation conversation : conversations) {
            LambdaQueryWrapper<QaMessage> countWrapper = new LambdaQueryWrapper<>();
            countWrapper.eq(QaMessage::getConversationId, conversation.getId());
            conversation.setMessageCount(Math.toIntExact(messageRepository.selectCount(countWrapper)));
        }
        return conversations;
    }

    /**
     * 删除对话
     */
    @Transactional
    public void deleteConversation(String conversationId) {
        // 先删除消息
        LambdaQueryWrapper<QaMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QaMessage::getConversationId, conversationId);
        messageRepository.delete(wrapper);
        
        // 再删除会话
        conversationRepository.deleteById(conversationId);
    }
}
