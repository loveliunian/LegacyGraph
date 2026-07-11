package io.github.legacygraph.service.source;

import io.github.legacygraph.dto.source.SourceDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * G-01: SourceRegistry — 资料源连接器注册中心。
 * <p>自动注入所有 {@link SourceConnector} 实现，统一调度 discover/readContent。
 * 通过 Feature Flag 控制是否启用新链路（默认 false，保留旧 ProjectScanner.discoverAllSources 路径）。</p>
 */
@Slf4j
@Service
public class SourceRegistry {

    private final List<SourceConnector> connectors;

    @Value("${legacygraph.source.connector.enabled:false}")
    private boolean connectorEnabled;

    @Autowired
    public SourceRegistry(List<SourceConnector> connectors) {
        this.connectors = connectors != null ? connectors : new ArrayList<>();
        log.info("SourceRegistry initialized with {} connectors", this.connectors.size());
    }

    /**
     * 是否启用新链路（Feature Flag）。
     */
    public boolean isConnectorEnabled() {
        return connectorEnabled;
    }

    /**
     * 发现项目下所有资料源（聚合所有连接器结果）。
     */
    public List<SourceDescriptor> discoverAll(String projectId) {
        List<SourceDescriptor> all = new ArrayList<>();
        for (SourceConnector connector : connectors) {
            try {
                List<SourceDescriptor> found = connector.discover(projectId);
                if (found != null) {
                    all.addAll(found);
                }
            } catch (Exception e) {
                log.warn("SourceConnector {} discover failed: {}", connector.sourceType(), e.getMessage());
            }
        }
        log.info("SourceRegistry discovered {} sources for projectId={}", all.size(), projectId);
        return all;
    }

    /**
     * 按类型发现资料源。
     */
    public List<SourceDescriptor> discoverByType(String projectId, String sourceType) {
        for (SourceConnector connector : connectors) {
            if (connector.sourceType().equals(sourceType)) {
                return connector.discover(projectId);
            }
        }
        return new ArrayList<>();
    }
}
