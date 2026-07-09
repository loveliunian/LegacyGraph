package io.github.legacygraph.verification;

import io.github.legacygraph.extractors.adapter.ScanContext;

/**
 * 外部验证适配器 —— 在本地抽取完成后，用外部工具对照校验图谱结果。
 * <p>
 * 与 ExtractionAdapter 的区别：前者"建图"（从源码抽取事实），后者"验图"（用外部工具验证已有图谱）。
 * </p>
 */
public interface ExternalVerificationAdapter {

    /**
     * 适配器名称（唯一标识）
     */
    String adapterName();

    /**
     * 优先级（数值越小越优先）
     */
    int priority();

    /**
     * 是否支持验证此扫描上下文
     */
    boolean supports(ScanContext context);

    /**
     * 执行验证，返回外部工具发现的节点/边/差异
     */
    VerificationResult verify(String projectId, String versionId, ScanContext context);

    /**
     * 外部工具健康检查
     */
    boolean checkHealth();
}
