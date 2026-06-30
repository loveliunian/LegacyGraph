package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TestResultUpdateServiceTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private TestResultRepository testResultRepository;
    @Mock
    private TestCaseRepository testCaseRepository;
    @Mock
    private ReviewRecordRepository reviewRecordRepository;

    private TestResultUpdateService testResultUpdateService;

    @Test
    void testConstruction() {
        testResultUpdateService = new TestResultUpdateService(
                neo4jGraphDao, testResultRepository,
                testCaseRepository, reviewRecordRepository);
        assertNotNull(testResultUpdateService);
    }
}
