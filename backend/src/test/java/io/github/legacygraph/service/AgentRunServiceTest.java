package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.github.legacygraph.entity.AgentRun;
import io.github.legacygraph.repository.AgentRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunServiceTest {

    @Mock
    private AgentRunRepository repository;

    @Test
    void historyReturnsRunsForProjectWithDerivedStatus() {
        AgentRun running = new AgentRun();
        running.setId(1L);
        running.setProjectId("project-1");
        running.setAgentType("codefact");
        running.setStartedAt(LocalDateTime.parse("2026-07-03T10:00:00"));
        running.setCreatedAt(LocalDateTime.parse("2026-07-03T10:00:00"));

        AgentRun review = new AgentRun();
        review.setId(2L);
        review.setProjectId("project-1");
        review.setAgentType("review");
        review.setNeedsHumanReview(1);
        review.setStartedAt(LocalDateTime.parse("2026-07-03T10:00:00"));
        review.setFinishedAt(LocalDateTime.parse("2026-07-03T10:00:05"));
        review.setCreatedAt(LocalDateTime.parse("2026-07-03T10:00:00"));

        when(repository.selectList(any())).thenReturn(List.of(running, review));

        AgentRunService service = new AgentRunService(repository);
        Map<String, Object> result = service.history("project-1", null, null, 20);

        List<?> list = (List<?>) result.get("list");
        assertEquals(2, list.size());
        assertEquals("RUNNING", ((Map<?, ?>) list.get(0)).get("status"));
        assertEquals("REVIEW", ((Map<?, ?>) list.get(1)).get("status"));
        assertEquals(5000L, ((Map<?, ?>) list.get(1)).get("durationMs"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AgentRun>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(repository).selectList(captor.capture());
        assertFalse(captor.getValue().getSqlSegment().isBlank());
    }
}
