package io.github.legacygraph.plugin;

import io.github.legacygraph.extractors.adapter.ScanContext;
import io.github.legacygraph.verification.VerificationResult;

/**
 * 插件适配器 —— 在扫描流程中调用用户选择的外部插件，对图谱做验证/增强/补漏。
 * <p>
 * 与 {@link io.github.legacygraph.verification.ExternalVerificationAdapter} 的区别：
 * 前者由用户在创建扫描任务时通过 pluginIds 显式选择，后者由系统按开关自动收集。
 * 两者产出相同的 {@link VerificationResult}，由 {@link io.github.legacygraph.verification.ResultFusionEngine} 统一融合。
 * </p>
 */
public interface PluginAdapter {

    /**
     * 插件 ID（与 PluginRegistry 中注册的 ID 一致，用于匹配用户选择的 pluginIds）
     */
    String pluginId();

    /**
     * 优先级（数值越小越优先）
     */
    int priority();

    /**
     * 是否支持此扫描上下文
     */
    boolean supports(ScanContext context);

    /**
     * 执行插件调用，返回验证/增强结果
     */
    VerificationResult invoke(String projectId, String versionId, ScanContext context);

    /**
     * 插件健康检查
     */
    boolean checkHealth();
}
