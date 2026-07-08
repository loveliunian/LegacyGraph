package io.github.legacygraph.task;

import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;
import io.github.legacygraph.repository.TestRunRepository;
import io.github.legacygraph.service.graph.GraphValidatorService;
import io.github.legacygraph.test.ApiTestExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.test.TestResultUpdateService;

@ExtendWith(MockitoExtension.class)
class TestExecutionSchedulerTest {

    @Mock
    private TestCaseRepository testCaseRepository;
    @Mock
    private TestResultRepository testResultRepository;
    @Mock
    private TestRunRepository testRunRepository;
    @Mock
    private ApiTestExecutor apiTestExecutor;
    @Mock
    private GraphValidatorService graphValidatorService;
    @Mock
    private TestResultUpdateService testResultUpdateService;

    @InjectMocks
    private TestExecutionScheduler scheduler;

    @Test
    void submitTestRun_returnsRunId() {
        lenient().when(testRunRepository.insert(any(io.github.legacygraph.entity.TestRun.class))).thenReturn(1);

        String runId = scheduler.submitTestRun("project-1", "v1", List.of("case-1"), "test");

        assertNotNull(runId);
        assertFalse(runId.isEmpty());
    }

    @Test
    void getStats_returnsValidStats() {
        TestExecutionScheduler.TestExecutionStats stats = scheduler.getStats();
        assertNotNull(stats);
    }

    @Test
    void cancelTestRun_updatesStatus() {
        when(testResultRepository.updateStatusByExecutionId("run-1", "CANCELLED")).thenReturn(1);

        assertDoesNotThrow(() -> scheduler.cancelTestRun("run-1"));
        verify(testResultRepository).updateStatusByExecutionId("run-1", "CANCELLED");
    }

    @Test
    void submitTestRun_emptyCaseIds_doesNotThrow() {
        String runId = scheduler.submitTestRun("project-1", "v1", List.of(), "test");
        assertNotNull(runId);
    }
}
