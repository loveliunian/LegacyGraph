package io.github.legacygraph.service;

import io.github.legacygraph.dto.CreateScanVersionRequest;
import io.github.legacygraph.dto.ScanProgressResponse;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScanVersionServiceTest {

    @Mock
    private ScanTaskRepository scanTaskRepository;

    @Mock
    private ScanVersionRepository scanVersionRepository;

    @InjectMocks
    private ScanVersionService scanVersionService;

    private String testProjectId = "test-project-1";
    private CreateScanVersionRequest createRequest;

    @BeforeEach
    void setUp() {
        createRequest = new CreateScanVersionRequest();
        createRequest.setVersionNo("v1.0.0");
        createRequest.setBranchName("main");
        createRequest.setCommitId("abc123");
        createRequest.setScanScope("src/");
    }

    @Test
    void testCreateScanVersion_Success() {
        // 因为是继承 ServiceImpl，我们这里主要验证业务逻辑
        ScanVersion result = scanVersionService.createScanVersion(testProjectId, createRequest);

        assertNotNull(result);
        assertEquals(testProjectId, result.getProjectId());
        assertEquals("v1.0.0", result.getVersionNo());
        assertEquals("main", result.getBranchName());
        assertEquals("abc123", result.getCommitId());
        assertEquals("src/", result.getScanScope());
        assertEquals("CREATED", result.getScanStatus());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
    }

    @Test
    void testGetScanProgress_VersionNotFound() {
        when(scanVersionRepository.selectById("nonexistent")).thenReturn(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> scanVersionService.getScanProgress("nonexistent"));

        assertTrue(exception.getMessage().contains("扫描版本不存在"));
    }

    @Test
    void testGetScanProgress_NoTasks() {
        ScanVersion version = new ScanVersion();
        version.setId("version-1");
        version.setScanStatus("CREATED");

        when(scanVersionRepository.selectById("version-1")).thenReturn(version);
        when(scanTaskRepository.lambdaQuery()).thenReturn(null);
        when(scanTaskRepository.lambdaQuery().eq(any(), any())).thenReturn(null);
        when(scanTaskRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(Collections.emptyList());

        ScanProgressResponse response = scanVersionService.getScanProgress("version-1");

        assertNotNull(response);
        assertEquals("version-1", response.getVersionId());
        assertEquals("CREATED", response.getStatus());
        assertEquals(0, response.getProgress());
        assertTrue(response.getTasks().isEmpty());
    }

    @Test
    void testGetScanProgress_WithTasks_MixedStatus() {
        ScanVersion version = new ScanVersion();
        version.setId("version-1");
        version.setScanStatus("RUNNING");

        List<ScanTask> tasks = new ArrayList<>();

        ScanTask task1 = new ScanTask();
        task1.setId("task-1");
        task1.setVersionId("version-1");
        task1.setTaskType("PARSE");
        task1.setTaskStatus("SUCCESS");
        tasks.add(task1);

        ScanTask task2 = new ScanTask();
        task2.setId("task-2");
        task2.setVersionId("version-1");
        task2.setTaskType("EXTRACT");
        task2.setTaskStatus("RUNNING");
        tasks.add(task2);

        when(scanVersionRepository.selectById("version-1")).thenReturn(version);
        when(scanTaskRepository.lambdaQuery().eq(ScanTask::getVersionId, "version-1").list()).thenReturn(tasks);

        ScanProgressResponse response = scanVersionService.getScanProgress("version-1");

        assertNotNull(response);
        assertEquals(50, response.getProgress()); // 1 out of 2 completed
        assertEquals(2, response.getTasks().size());
    }

    @Test
    void testGetScanProgress_AllCompleted() {
        ScanVersion version = new ScanVersion();
        version.setId("version-1");
        version.setScanStatus("SUCCESS");

        List<ScanTask> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ScanTask task = new ScanTask();
            task.setId("task-" + i);
            task.setVersionId("version-1");
            task.setTaskStatus("SUCCESS");
            tasks.add(task);
        }

        when(scanVersionRepository.selectById("version-1")).thenReturn(version);
        when(scanTaskRepository.lambdaQuery().eq(ScanTask::getVersionId, "version-1").list()).thenReturn(tasks);

        ScanProgressResponse response = scanVersionService.getScanProgress("version-1");

        assertEquals(100, response.getProgress());
    }

    @Test
    void testUpdateScanStatus_VersionNotFound() {
        when(scanVersionRepository.selectById("version-1")).thenReturn(null);

        // Should not throw, just do nothing
        assertDoesNotThrow(() -> scanVersionService.updateScanStatus("version-1", "RUNNING"));
    }

    @Test
    void testUpdateScanStatus_SetRunning() {
        ScanVersion version = new ScanVersion();
        version.setId("version-1");

        when(scanVersionRepository.selectById("version-1")).thenReturn(version);

        scanVersionService.updateScanStatus("version-1", "RUNNING");

        assertEquals("RUNNING", version.getScanStatus());
        assertNotNull(version.getStartedAt());
        assertNotNull(version.getUpdatedAt());
    }

    @Test
    void testUpdateScanStatus_SetSuccess() {
        ScanVersion version = new ScanVersion();
        version.setId("version-1");

        when(scanVersionRepository.selectById("version-1")).thenReturn(version);

        scanVersionService.updateScanStatus("version-1", "SUCCESS");

        assertEquals("SUCCESS", version.getScanStatus());
        assertNotNull(version.getFinishedAt());
        assertNotNull(version.getUpdatedAt());
    }

    @Test
    void testUpdateScanStatus_SetFailed() {
        ScanVersion version = new ScanVersion();
        version.setId("version-1");

        when(scanVersionRepository.selectById("version-1")).thenReturn(version);

        scanVersionService.updateScanStatus("version-1", "FAILED");

        assertEquals("FAILED", version.getScanStatus());
        assertNotNull(version.getFinishedAt());
        assertNotNull(version.getUpdatedAt());
    }
}
