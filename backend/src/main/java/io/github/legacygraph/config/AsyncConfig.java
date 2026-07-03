package io.github.legacygraph.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.AsyncConfigurer;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 异步任务线程池配置 — 全部使用 Java 21 虚拟线程。
 * <p>
 * 虚拟线程在 I/O 阻塞时自动让出 CPU，相比固定大小平台线程池更高效。
 * 原 corePoolSize/maxPoolSize/queueCapacity 等限流参数对虚拟线程无意义（虚拟线程几乎无内存开销），
 * 如需限制并发数请使用 Semaphore 在业务层控制。
 * </p>
 */
@Slf4j
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /**
     * 通用异步任务执行器（虚拟线程）
     * 用于：扫描任务、报告生成、向量化、QA 流式问答等一般任务
     */
    @Primary
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        log.info("通用异步任务执行器初始化完成 (虚拟线程)");
        return executor;
    }

    /**
     * IO密集型任务执行器（虚拟线程）
     * 用于：文件上传、下载、MinIO操作等IO密集型任务
     */
    @Bean(name = "ioTaskExecutor")
    public Executor ioTaskExecutor() {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        log.info("IO密集型任务执行器初始化完成 (虚拟线程)");
        return executor;
    }

    /**
     * 测试执行专用执行器（虚拟线程）
     * 用于：API测试、E2E测试、数据库断言测试等
     */
    @Bean(name = "testExecutor")
    public Executor testExecutor() {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        log.info("测试执行任务执行器初始化完成 (虚拟线程)");
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return taskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable ex, Method method, Object... params) ->
            log.error("异步任务执行异常: 方法={}, 参数={}", method.getName(), params, ex);
    }
}
