package io.github.legacygraph.extractors.adapter;

/**
 * 抽取适配器接口。
 * <p>
 * 每种技术栈（Java/Spring, MyBatis, Vue, NestJS, Django 等）提供一个 Adapter 实现。
 * Registry 根据项目资产、语言、框架、文件类型自动选择合适的 Adapter。
 * </p>
 *
 * <h3>设计原则（见 doc/架构与三类图谱AI优化建议.md 3.2）</h3>
 * <ul>
 *   <li>ProjectScanner 只负责资产发现、任务状态、编排和失败隔离</li>
 *   <li>Adapter 封装具体的解析逻辑，Scanner 不知道技术栈细节</li>
 *   <li>AI 可作为 SemanticEnrichmentAdapter 在结构化证据之后做补全</li>
 * </ul>
 */
public interface ExtractionAdapter {

    /**
     * 判断此适配器是否支持处理指定的资产。
     */
    boolean supports(ScanContext context, SourceAsset asset);

    /**
     * 从资产中抽取结构化证据。
     */
    ExtractionResult extract(ScanContext context, SourceAsset asset);

    /**
     * 返回此适配器的能力描述。
     */
    AdapterCapability capability();
}
