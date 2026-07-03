package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.EnhancedQaAgent;
import io.github.legacygraph.entity.QaFeedback;
import io.github.legacygraph.repository.QaFeedbackRepository;
import io.github.legacygraph.service.qa.ConversationContextManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EnhancedQaControllerTest {

    @Mock
    private EnhancedQaAgent enhancedQaAgent;
    @Mock
    private ConversationContextManager conversationManager;
    @Mock
    private QaFeedbackRepository feedbackRepository;
    @Mock
    private java.util.concurrent.Executor taskExecutor;

    @Test
    void submitFeedbackPersistsUsedEvidenceIdsForQualityLoop() {
        EnhancedQaController controller = new EnhancedQaController(
                enhancedQaAgent, conversationManager, feedbackRepository, new ObjectMapper(), taskExecutor);

        EnhancedQaController.FeedbackRequest request = new EnhancedQaController.FeedbackRequest();
        request.setMessageId("msg-1");
        request.setConversationId("conv-1");
        request.setProjectId("proj-1");
        request.setHelpful(false);
        request.setUsedEvidenceIds(List.of("node-1", "doc-1"));

        controller.submitFeedback(request);

        ArgumentCaptor<QaFeedback> feedbackCaptor = ArgumentCaptor.forClass(QaFeedback.class);
        verify(feedbackRepository).save(feedbackCaptor.capture());
        assertEquals("[\"node-1\",\"doc-1\"]", feedbackCaptor.getValue().getUsedEvidenceIds());
    }
}
