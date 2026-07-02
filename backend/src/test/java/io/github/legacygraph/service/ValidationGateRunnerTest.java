package io.github.legacygraph.service;

import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.entity.ValidationGate;
import io.github.legacygraph.repository.TestResultRepository;
import io.github.legacygraph.repository.ValidationGateRepository;
import io.github.legacygraph.task.TestExecutionScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ValidationGateRunnerTest {

    @Mock private ValidationGateRepository validationGateRepository;
    @Mock private TestExecutionScheduler testExecutionScheduler;
    @Mock private TestResultRepository testResultRepository;
    @Mock private TransactionTemplate transactionTemplate;

    private ValidationGateRunner runner;

    @BeforeEach
    void setUp() {
        // Mock TransactionTemplate to directly run the callback
        lenient().doAnswer(invocation -> {
            var consumer = invocation.getArgument(0, java.util.function.Consumer.class);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().doAnswer(invocation -> {
            var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());

        runner = new ValidationGateRunner(validationGateRepository, testExecutionScheduler,
                testResultRepository, 50, 1, transactionTemplate);
    }

    private ValidationGate gate(String type, String command) {
        ValidationGate g = new ValidationGate();
        g.setId("g-" + type);
        g.setChangeTaskId("chg-1");
        g.setGateType(type);
        g.setCommand(command);
        g.setResult("PENDING");
        return g;
    }

    private TestResult result(String status) {
        TestResult r = new TestResult();
        r.setExecutionId("run-1");
        r.setResultStatus(status);
        return r;
    }

    @Test
    void staticGate_zeroExit_passes() {
        ValidationGate g = gate("STATIC", "exit 0");
        ValidationGate result = runner.runGate(g, ValidationGateRunner.GateContext.builder().build());
        assertEquals("PASSED", result.getResult());
        assertNotNull(result.getFinishedAt());
    }

    @Test
    void staticGate_nonZeroExit_fails() {
        ValidationGate g = gate("STATIC", "exit 3");
        ValidationGate result = runner.runGate(g, ValidationGateRunner.GateContext.builder().build());
        assertEquals("FAILED", result.getResult());
    }

    @Test
    void commandGate_noCommand_treatedAsPass() {
        ValidationGate g = gate("MIGRATION", null);
        ValidationGate result = runner.runGate(g, ValidationGateRunner.GateContext.builder().build());
        assertEquals("PASSED", result.getResult());
    }

    @Test
    void testGate_noCaseIds_treatedAsPass() {
        ValidationGate g = gate("UNIT", null);
        ValidationGate result = runner.runGate(g, ValidationGateRunner.GateContext.builder()
                .projectId("p1").versionId("v1").build());
        assertEquals("PASSED", result.getResult());
        verify(testExecutionScheduler, never()).submitTestRun(any(), any(), anyList(), any());
    }

    @Test
    void testGate_withCaseIds_waitsForPassedResults() {
        ValidationGate g = gate("API", null);
        when(testExecutionScheduler.submitTestRun(eq("p1"), eq("v1"), anyList(), any()))
                .thenReturn("run-1");
        when(testResultRepository.findByExecutionId("run-1"))
                .thenReturn(List.of(result("PASSED")));
        ValidationGate result = runner.runGate(g, ValidationGateRunner.GateContext.builder()
                .projectId("p1").versionId("v1").caseIds(List.of("case-1")).environment("test").build());
        assertEquals("PASSED", result.getResult());
        assertEquals("testRun:run-1", result.getReportUri());
        verify(testExecutionScheduler).submitTestRun(eq("p1"), eq("v1"), anyList(), eq("test"));
        verify(testResultRepository).findByExecutionId("run-1");
    }

    @Test
    void testGate_withCaseIds_failedResultFailsGate() {
        ValidationGate g = gate("API", null);
        when(testExecutionScheduler.submitTestRun(eq("p1"), eq("v1"), anyList(), any()))
                .thenReturn("run-1");
        when(testResultRepository.findByExecutionId("run-1"))
                .thenReturn(List.of(result("FAILED")));
        ValidationGate result = runner.runGate(g, ValidationGateRunner.GateContext.builder()
                .projectId("p1").versionId("v1").caseIds(List.of("case-1")).environment("test").build());
        assertEquals("FAILED", result.getResult());
        assertEquals("testRun:run-1", result.getReportUri());
    }

    @Test
    void runAll_anyFail_returnsFalse() {
        when(validationGateRepository.lambdaQuery()).thenReturn(
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class, RETURNS_DEEP_STUBS));
        when(validationGateRepository.lambdaQuery().eq(any(), any()).list())
                .thenReturn(List.of(gate("STATIC", "exit 0"), gate("STATIC", "exit 1")));

        boolean allPassed = runner.runAll("chg-1", ValidationGateRunner.GateContext.builder().build());
        assertFalse(allPassed);
    }
}
