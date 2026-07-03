package io.github.legacygraph.service;

import io.github.legacygraph.service.qa.ConversationContextManager;

import io.github.legacygraph.entity.QaConversation;
import io.github.legacygraph.repository.QaConversationRepository;
import io.github.legacygraph.repository.QaMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationContextManagerTest {

    @Mock
    private QaConversationRepository conversationRepository;
    @Mock
    private QaMessageRepository messageRepository;

    @Test
    void listConversationsFillsMessageCountForSidebar() {
        ConversationContextManager manager = new ConversationContextManager(
                conversationRepository, messageRepository);

        QaConversation conversation = new QaConversation();
        conversation.setId("conv-1");
        conversation.setProjectId("proj-1");
        when(conversationRepository.selectList(any())).thenReturn(List.of(conversation));
        when(messageRepository.selectCount(any())).thenReturn(2L);

        List<QaConversation> result = manager.listConversations("proj-1");

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).getMessageCount());
    }
}
