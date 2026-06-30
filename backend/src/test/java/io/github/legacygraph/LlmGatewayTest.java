package io.github.legacygraph;

import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.llm.PiiMaskingService;
import io.github.legacygraph.llm.PromptTemplateLoader;
import io.github.legacygraph.repository.PromptRunRepository;
import io.github.legacygraph.service.LlmProviderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class LlmGatewayTest {

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock
    private PromptRunRepository promptRunRepository;
    @Mock
    private PromptTemplateLoader templateLoader;
    @Mock
    private PiiMaskingService piiMaskingService;
    @Mock
    private LlmProviderService llmProviderService;

    @Test
    void testConstruction() {
        var llmGateway = new LlmGateway(objectMapper, promptRunRepository, templateLoader,
                piiMaskingService, llmProviderService);
        assertNotNull(llmGateway);
    }
}
