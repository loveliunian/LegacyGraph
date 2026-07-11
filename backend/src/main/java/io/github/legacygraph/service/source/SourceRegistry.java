package io.github.legacygraph.service.source;

import io.github.legacygraph.dto.source.SourceDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 源连接器注册中心（G-01）。
 * <p>
 * 自动收集所有 {@link SourceConnector} 实现，按 {@link SourceConnector#supportedSourceType()}
 * 建立路由表，供调用方按 sourceType 解析到对应连接器。
 * {@link #discoverAll(String)} 汇聚所有连接器的发现结果，作为扫描范围的统一入口。
 * </p>
 */
@Slf4j
@Service
public class SourceRegistry {

    /** 按 sourceType 索引的连接器表 */
    private final Map<String, SourceConnector> connectorByType;

    /** 全部连接器列表 */
    private final List<SourceConnector> connectors;

    public SourceRegistry(List<SourceConnector> connectors) {
        this.connectors = connectors == null ? List.of() : connectors;
        Map<String, SourceConnector> map = new HashMap<>();
        for (SourceConnector connector : this.connectors) {
            String type = connector.supportedSourceType();
            if (type == null || type.isBlank()) {
                log.warn("SourceConnector [{}] 未声明 supportedSourceType，跳过注册",
                        connector.getClass().getName());
                continue;
            }
            SourceConnector existing = map.put(type, connector);
            if (existing != null) {
                log.warn("sourceType [{}] 存在多个连接器实现，后者覆盖前者：{} -> {}",
                        type, existing.getClass().getName(), connector.getClass().getName());
            }
        }
        this.connectorByType = map;
        log.info("SourceRegistry 已注册 {} 个 SourceConnector，类型：{}",
                this.connectors.size(), this.connectorByType.keySet());
    }

    /**
     * 按 sourceType 解析到对应连接器。
     *
     * @param projectId  项目 ID（预留：未来可用于按项目选择连接器实例）
     * @param sourceType 源类型（CODE | DOC | DB | RUN | EXTERNAL）
     * @return 对应的连接器
     * @throws IllegalStateException 若该 sourceType 未注册任何连接器
     */
    public SourceConnector resolve(String projectId, String sourceType) {
        SourceConnector connector = connectorByType.get(sourceType);
        if (connector == null) {
            throw new IllegalStateException(
                    "未找到 sourceType=[" + sourceType + "] 对应的 SourceConnector（projectId=" + projectId + "）");
        }
        return connector;
    }

    /**
     * 调用所有已注册连接器的 discover，合并并返回全部源描述符。
     *
     * @param projectId 项目 ID
     * @return 合并后的源描述符列表
     */
    public List<SourceDescriptor> discoverAll(String projectId) {
        List<SourceDescriptor> all = new ArrayList<>();
        for (SourceConnector connector : connectors) {
            try {
                List<SourceDescriptor> found = connector.discover(projectId);
                if (found != null && !found.isEmpty()) {
                    all.addAll(found);
                }
            } catch (Exception e) {
                log.error("SourceConnector [{}] discover 失败（projectId={}）",
                        connector.getClass().getName(), projectId, e);
            }
        }
        return all;
    }
}
