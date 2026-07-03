package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.service.AgentRunService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunControllerTest {

    @Mock
    private AgentRunService agentRunService;

    @Test
    void historyDelegatesToService() {
        when(agentRunService.history("project-1", null, null, 20))
                .thenReturn(Map.of("list", List.of(Map.of("status", "SUCCESS"))));

        AgentRunController controller = new AgentRunController(agentRunService);
        Result<Map<String, Object>> result = controller.history("project-1", null, null, 20);

        assertEquals(0, result.getCode());
        List<?> list = (List<?>) result.getData().get("list");
        assertEquals(1, list.size());
        assertEquals("SUCCESS", ((Map<?, ?>) list.get(0)).get("status"));
        verify(agentRunService).history("project-1", null, null, 20);
    }
}
