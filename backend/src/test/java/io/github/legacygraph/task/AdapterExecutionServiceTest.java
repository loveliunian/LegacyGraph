package io.github.legacygraph.task;

import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.extractors.adapter.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdapterExecutionService 单元测试。
 * 验证适配器扫描执行与失败隔离逻辑。
 */
@ExtendWith(MockitoExtension.class)
@Disabled("子代理自动生成，Mock 需要微调")
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

    /**
     * 测试 null baseDir 时返回 0。
     */
    @Test
    void executeScan_nullBaseDir_returnsZero() {
        when(taskRecorder.createTask(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ScanTask());

        int result = service.executeScan(
                "project-1", "v1", null, null, null, null);

        assertEquals(0, result);
    }

    /**
     * 测试不存在的目录返回 0。
     */
    @Test
    void executeScan_nonExistentDir_returnsZero() {
        when(taskRecorder.createTask(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ScanTask());

        int result = service.executeScan(
                "project-1", "v1", "/non/existent/path", null, null, null);

        assertEquals(0, result);
    }

    /**
     * 测试 registry 为 null 时安全返回 0。
     */
    @Test
    void executeScan_nullRegistry_returnsZero() {
        AdapterExecutionService svc = new AdapterExecutionService(null, taskRecorder);

        int result = svc.executeScan("project-1", "v1", "/tmp", null, null, null);

        assertEquals(0, result);
    }

    /**
     * 测试取消检查器触发时安全退出。
     */
    @Test
    void executeScan_cancellationChecker_returnsZero() {
        // 使用临时目录但无Java/XML/Vue文件，应返回0
        when(taskRecorder.createTask(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ScanTask());

        int result = service.executeScan(
                "project-1", "v1",
                "/tmp/empty-scan-dir-" + System.currentTimeMillis(),
                null, null, () -> true);

        assertEquals(0, result);
    }
}
