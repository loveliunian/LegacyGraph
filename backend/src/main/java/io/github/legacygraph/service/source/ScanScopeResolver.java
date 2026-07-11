package io.github.legacygraph.service.source;

import io.github.legacygraph.dto.source.SourceDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 扫描范围解析器（G-01）。
 * <p>
 * 通过 {@link SourceRegistry#discoverAll(String)} 汇聚所有源连接器的发现结果，
 * 返回合并后的源描述符列表，作为扫描流程的统一范围输入。
 * </p>
 */
@Slf4j
@Service("sourceScanScopeResolver")
public class ScanScopeResolver {

    private final SourceRegistry sourceRegistry;

    public ScanScopeResolver(SourceRegistry sourceRegistry) {
        this.sourceRegistry = sourceRegistry;
    }

    /**
     * 解析项目下的扫描范围。
     * <p>
     * 调用 {@link SourceRegistry#discoverAll(String)} 获取全部已注册连接器发现的源资产，
     * 合并后返回。
     * </p>
     *
     * @param projectId 项目 ID
     * @return 合并后的源描述符列表
     */
    public List<SourceDescriptor> resolveScanScope(String projectId) {
        log.debug("解析扫描范围开始：projectId={}", projectId);
        List<SourceDescriptor> descriptors = sourceRegistry.discoverAll(projectId);
        log.debug("解析扫描范围完成：projectId={}，共 {} 个源", projectId, descriptors.size());
        return descriptors;
    }
}
