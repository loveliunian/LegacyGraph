package io.github.legacygraph;

import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.llm.PiiMaskingService;
import io.github.legacygraph.llm.PromptTemplateLoader;
import io.github.legacygraph.repository.PromptRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.openai.OpenAiChatModel;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class LlmGatewayTest {

    @Mock
    private OpenAiChatModel openAiChatModel;
    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock
    private PromptRunRepository promptRunRepository;
    @Mock
    private PromptTemplateLoader templateLoader;
    @Mock
    private PiiMaskingService piiMaskingService;

    private LlmGateway llmGateway;

    @Test
    void testConstruction() {
        llmGateway = new LlmGateway(openAiChatModel, objectMapper, promptRunRepository, templateLoader, piiMaskingService);
        assertNotNull(llmGateway);
    }
}
