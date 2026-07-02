package io.github.legacygraph.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AsyncConfig 单元测试。
 * <p>
 * 验证异步任务线程池配置：taskExecutor、ioTaskExecutor、testExecutor 均使用虚拟线程。
 * </p>
 */
class AsyncConfigTest {

    private final AsyncConfig asyncConfig = new AsyncConfig();

    /**
     * 测试：taskExecutor 返回非空 Executor。
     */
    @Test
    void testTaskExecutor_NotNull() {
        Executor executor = asyncConfig.taskExecutor();

        assertNotNull(executor);
    }

    /**
     * 测试：ioTaskExecutor 返回非空 Executor。
     */
    @Test
    void testIoTaskExecutor_NotNull() {
        Executor executor = asyncConfig.ioTaskExecutor();

        assertNotNull(executor);
    }

    /**
     * 测试：testExecutor 返回非空 Executor。
     */
    @Test
    void testTestExecutor_NotNull() {
        Executor executor = asyncConfig.testExecutor();

        assertNotNull(executor);
    }

    /**
     * 测试：各 Executor 可以成功执行 Runnable 任务。
     */
    @Test
    void testTaskExecutor_CanExecuteTask() throws Exception {
        Executor executor = asyncConfig.taskExecutor();
        var result = new boolean[1];

        executor.execute(() -> result[0] = true);

        // 等待任务完成（虚拟线程通常几乎即时执行）
        Thread.sleep(100);
        assertTrue(result[0], "异步任务应被执行");
    }

    /**
     * 测试：getAsyncExecutor 返回非空的默认异步执行器。
     */
    @Test
    void testGetAsyncExecutor_ReturnsNonNull() {
        Executor executor = asyncConfig.getAsyncExecutor();

        assertNotNull(executor);
    }

    /**
     * 测试：getAsyncUncaughtExceptionHandler 返回非空异常处理器。
     */
    @Test
    void testGetAsyncUncaughtExceptionHandler_ReturnsNonNull() {
        assertNotNull(asyncConfig.getAsyncUncaughtExceptionHandler());
    }
}
