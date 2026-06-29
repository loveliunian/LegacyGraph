package io.github.legacygraph;

import io.github.legacygraph.test.DbAssertionExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class DbAssertionExecutorTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private io.github.legacygraph.repository.TestResultRepository testResultRepository;
    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    void testConstruction() {
        DbAssertionExecutor executor = new DbAssertionExecutor(dataSource, testResultRepository, objectMapper);
        assertNotNull(executor);
    }

    @Test
    void testExecuteExists_NoConnection_ReturnsFailed() {
        // mock dataSource.getConnection() 默认返回 null，executeQuery 应捕获异常并返回未通过结果
        DbAssertionExecutor executor = new DbAssertionExecutor(dataSource, testResultRepository, objectMapper);

        DbAssertionExecutor.AssertionResult result = executor.executeExists("SELECT 1");

        assertNotNull(result);
        assertFalse(result.isPassed());
    }
}
