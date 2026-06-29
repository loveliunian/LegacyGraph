package io.github.legacygraph;

import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.test.ApiTestExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiTestExecutorTest {

    @Mock
    private io.github.legacygraph.repository.TestResultRepository testResultRepository;
    @Mock
    private io.github.legacygraph.repository.TestAssertionRepository testAssertionRepository;
    @Mock
    private io.github.legacygraph.test.DbAssertionExecutor dbAssertionExecutor;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void testConstruction() {
        ApiTestExecutor executor = new ApiTestExecutor(testResultRepository, testAssertionRepository,
                dbAssertionExecutor, objectMapper);
        assertNotNull(executor);
    }

    @Test
    void testExecute_NoApiStep_ReturnsError() {
        // 用例步骤里没有 API 调用步骤，execute 应捕获异常并返回 ERROR 状态而非抛出
        when(testResultRepository.insert(any(TestResult.class))).thenReturn(1);

        ApiTestExecutor executor = new ApiTestExecutor(testResultRepository, testAssertionRepository,
                dbAssertionExecutor, objectMapper);

        TestCase testCase = new TestCase();
        testCase.setId("tc-1");
        testCase.setCaseCode("TC-1");
        testCase.setCaseName("no api step");
        testCase.setSteps("[]");
        testCase.setExpectedResult("{}");
        testCase.setPreconditions("[]");

        TestResult result = executor.execute(testCase, "http://localhost", "exec-1");

        assertNotNull(result);
        assertEquals("ERROR", result.getResultStatus());
        assertNotNull(result.getErrorMessage());
    }
}
