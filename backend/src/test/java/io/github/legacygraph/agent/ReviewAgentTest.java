package io.github.legacygraph.agent;

import io.github.legacygraph.agent.ReviewAgent;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ReviewAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private ReviewAgent reviewAgent;

    @Test
    void testConstruction() {
        assertNotNull(reviewAgent);
    }
}
