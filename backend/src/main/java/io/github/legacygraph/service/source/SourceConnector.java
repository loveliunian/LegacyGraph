package io.github.legacygraph.service.source;

import io.github.legacygraph.dto.source.SourceDescriptor;

import java.util.List;

/**
 * G-01: SourceConnector 抽象 — 统一五类资料源（代码/文档/数据库元数据/运行证据/外部API）的发现与读取。
 * <p>每个具体连接器实现此接口，由 {@link SourceRegistry} 统一注册和发现。
 * 默认通过 Feature Flag {@code legacygraph.source.connector.enabled=false} 控制是否启用新链路。</p>
 */
public interface SourceConnector {

    /**
     * 资料源类型标识：CODE / DOCUMENT / DATABASE / RUNTIME_EVIDENCE / EXTERNAL_API
     */
    String sourceType();

    /**
     * 发现项目下的所有资料源描述符。
     *
     * @param projectId 项目 ID
     * @return 资料源描述符列表
     */
    List<SourceDescriptor> discover(String projectId);

    /**
     * 读取资料源内容（流式，避免大文件 OOM）。
     *
     * @param descriptor 资料源描述符
     * @return 内容字符串
     */
    String readContent(SourceDescriptor descriptor);

    /**
     * 是否支持增量发现（基于快照对比变更）。
     */
    default boolean supportsIncremental() {
        return false;
    }
}
