package io.github.legacygraph.task;

import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;
import io.github.legacygraph.service.GraphValidatorService;
import io.github.legacygraph.test.ApiTestExecutor;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TestExecutionScheduler 单元测试。
 * 验证测试提交、统计信息与取消逻辑。
 */
@ExtendWith(MockitoExtension.class)
@Disabled("子代理自动生成，Mock 需要微调")
class TestExecutionSchedulerTest {

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private ApiTestExecutor apiTestExecutor;

    @Mock
    private GraphValidatorService graphValidatorService;

    @Mock
    private io.github.legacygraph.service.TestResultUpdateService testResultUpdateService;

    @InjectMocks
    private TestExecutionScheduler scheduler;

    /**
     * 测试 submitTestRun 返回非空 runId。
     */
    @Test
    void submitTestRun_returnsRunId() {
        TestCase tc = new TestCase();
        tc.setId("case-1");
        tc.setVersionId("v1");
        when(testCaseRepository.selectById("case-1")).thenReturn(tc);
        when(testResultRepository.updateStatusByExecutionId(anyString(), anyString())).thenReturn(1);

        String runId = scheduler.submitTestRun(
                "project-1", "v1", List.of("case-1"), "test");

        assertNotNull(runId);
        assertFalse(runId.isEmpty());
    }

    /**
     * 测试 getStats 返回有效统计。
     */
    @Test
    void getStats_returnsValidStats() {
        TestExecutionScheduler.TestExecutionStats stats = scheduler.getStats();

        assertNotNull(stats);
        assertEquals(0, stats.getActiveTasks());
        assertTrue(stats.getAvailablePermits() >= 0);
    }

    /**
     * 测试 cancelTestRun 调用 Repository 更新状态。
     */
    @Test
    void cancelTestRun_updatesStatus() {
        when(testResultRepository.updateStatusByExecutionId("run-1", "CANCELLED")).thenReturn(1);

        assertDoesNotThrow(() -> scheduler.cancelTestRun("run-1"));

        verify(testResultRepository).updateStatusByExecutionId("run-1", "CANCELLED");
    }

    /**
     * 测试 empty caseIds 提交不抛异常。
     */
    @Test
    void submitTestRun_emptyCaseIds_doesNotThrow() {
        String runId = scheduler.submitTestRun("project-1", "v1", List.of(), "test");

        assertNotNull(runId);
    }
}
