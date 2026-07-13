package io.github.legacygraph.service.sandbox;

import io.github.legacygraph.dto.sandbox.SandboxRequest;
import io.github.legacygraph.dto.sandbox.SandboxResult;

/**
 * 沙箱执行服务 — 在隔离环境中验证补丁（阶段三-3.1）。
 * <p>
 * 支持两种模式：
 * <ul>
 *   <li>LOCAL：本地执行（适用于有 localPath 的 CodeRepo）</li>
 *   <li>CONTAINER：容器化执行（适用于隔离验证，后续扩展）</li>
 * </ul>
 * </p>
 * <p>
 * 与现有系统的对接：
 * <ul>
 *   <li>复用 {@link io.github.legacygraph.service.test.ValidationGateRunner} 作为底层执行器</li>
 *   <li>复用 TestExecutionScheduler 作为测试调度</li>
 * </ul>
 * </p>
 */
public interface SandboxExecutor {

    /**
     * 在沙箱环境中执行验证。
     *
     * @param request 沙箱执行请求
     * @return 执行结果
     */
    SandboxResult execute(SandboxRequest request);
}
