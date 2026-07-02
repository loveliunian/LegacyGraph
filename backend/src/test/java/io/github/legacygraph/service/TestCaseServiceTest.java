package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.builder.FeatureSliceBuilder;
import io.github.legacygraph.builder.ScenarioDSLBuilder;
import io.github.legacygraph.dao.Neo4jGraphDao;
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
    private Neo4jGraphDao neo4jGraphDao;
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
    @Mock
    private FeatureSliceBuilder featureSliceBuilder;
    @Mock
    private ScenarioDSLBuilder scenarioDSLBuilder;

    private TestCaseService testCaseService;

    @Test
    void testConstruction() {
        testCaseService = new TestCaseService(neo4jGraphDao, testCaseRepository,
                testResultRepository, objectMapper, apiTestExecutor,
                dbAssertionExecutor, e2eTestExecutor, testResultUpdateService,
                featureSliceBuilder, scenarioDSLBuilder);
        assertNotNull(testCaseService);
    }
}
