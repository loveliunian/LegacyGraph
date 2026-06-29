package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.repository.*;
import io.github.legacygraph.test.ApiTestExecutor;
import io.github.legacygraph.test.DbAssertionExecutor;
import io.github.legacygraph.test.E2eTestExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TestCaseServiceTest {

    @Mock
    private GraphNodeRepository graphNodeRepository;
    @Mock
    private TestCaseRepository testCaseRepository;
    @Mock
    private TestResultRepository testResultRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ApiTestExecutor apiTestExecutor;
    @Mock
    private DbAssertionExecutor dbAssertionExecutor;
    @Mock
    private E2eTestExecutor e2eTestExecutor;
    @Mock
    private TestResultUpdateService testResultUpdateService;

    private TestCaseService testCaseService;

    @Test
    void testConstruction() {
        testCaseService = new TestCaseService(graphNodeRepository, testCaseRepository,
                testResultRepository, objectMapper, apiTestExecutor,
                dbAssertionExecutor, e2eTestExecutor, testResultUpdateService);
        assertNotNull(testCaseService);
    }
}
