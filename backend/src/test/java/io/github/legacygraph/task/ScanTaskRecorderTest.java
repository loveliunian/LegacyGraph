package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.repository.ScanTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ScanTaskRecorder 单元测试。
 * 验证扫描子任务的创建、进度记录与完成标记。
 */
@ExtendWith(MockitoExtension.class)
class ScanTaskRecorderTest {

    @Mock
    private ScanTaskRepository scanTaskRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ScanTaskRecorder recorder;

    /**
     * 测试 createTask 创建 RUNNING 状态子任务并入库。
     */
    @Test
    void createTask_createsRunningTask() {
        when(scanTaskRepository.insert(any(ScanTask.class))).thenReturn(1);

        ScanTask task = recorder.createTask("project-1", "v1", "SCAN", "扫描任务");

        assertNotNull(task);
        assertNotNull(task.getId());
        assertEquals("project-1", task.getProjectId());
        assertEquals("v1", task.getVersionId());
        assertEquals("SCAN", task.getTaskType());
        assertEquals("RUNNING", task.getTaskStatus());
        verify(scanTaskRepository).insert(any(ScanTask.class));
    }

    /**
     * 测试 completeTask 成功状态更新。
     */
    @Test
    void completeTask_success_updatesStatus() {
        ScanTask task = new ScanTask();
        task.setId("task-1");
        task.setProjectId("project-1");
        task.setVersionId("v1");
        task.setTaskType("SCAN");
        task.setTaskName("扫描任务");
        when(scanTaskRepository.updateById(any(ScanTask.class))).thenReturn(1);

        recorder.completeTask(task, "完成扫描 10 个文件", null);

        assertEquals("SUCCESS", task.getTaskStatus());
        assertNull(task.getErrorMessage());
        assertNotNull(task.getFinishedAt());
        verify(scanTaskRepository).updateById(eq(task));
    }

    /**
     * 测试 completeTask 失败状态更新。
     */
    @Test
    void completeTask_failure_updatesStatus() {
        ScanTask task = new ScanTask();
        task.setId("task-2");
        when(scanTaskRepository.updateById(any(ScanTask.class))).thenReturn(1);

        recorder.completeTask(task, null, "扫描异常");

        assertEquals("FAILED", task.getTaskStatus());
        assertEquals("扫描异常", task.getErrorMessage());
    }

    /**
     * 测试 logProgress null task 安全返回。
     */
    @Test
    void logProgress_nullTask_doesNotThrow() {
        assertDoesNotThrow(() -> recorder.logProgress(null, 5, 10, "files"));
    }

    /**
     * 测试 logProgress total 为 0 时不抛异常。
     */
    @Test
    void logProgress_zeroTotal_doesNotThrow() {
        ScanTask task = new ScanTask();
        task.setId("task-3");
        task.setProjectId("project-1");
        task.setVersionId("v1");

        assertDoesNotThrow(() -> recorder.logProgress(task, 0, 0, "files"));
    }
}
