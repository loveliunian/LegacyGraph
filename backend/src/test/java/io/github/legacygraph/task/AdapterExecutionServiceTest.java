package io.github.legacygraph.task;

import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.extractors.adapter.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdapterExecutionServiceTest {

    @Mock
    private ExtractionAdapterRegistry adapterRegistry;

    @Mock
    private ScanTaskRecorder taskRecorder;

    private AdapterExecutionService service;

    @BeforeEach
    void setUp() {
        service = new AdapterExecutionService(adapterRegistry, taskRecorder);
    }

    @Test
    void executeScan_nullBaseDir_returnsZero() {
        int result = service.executeScan("project-1", "v1", null, null, null, null);
        assertEquals(0, result);
    }

    @Test
    void executeScan_nonExistentDir_returnsZero() {
        int result = service.executeScan("project-1", "v1", "/non/existent/path", null, null, null);
        assertEquals(0, result);
    }

    @Test
    void executeScan_nullRegistry_returnsZero() {
        AdapterExecutionService svc = new AdapterExecutionService(null, taskRecorder);
        int result = svc.executeScan("project-1", "v1", "/tmp", null, null, null);
        assertEquals(0, result);
    }

    @Test
    void executeScan_cancellationChecker_returnsZero() {
        int result = service.executeScan("project-1", "v1",
                "/tmp/empty-scan-dir-" + System.currentTimeMillis(),
                null, null, () -> true);
        assertEquals(0, result);
    }
}
