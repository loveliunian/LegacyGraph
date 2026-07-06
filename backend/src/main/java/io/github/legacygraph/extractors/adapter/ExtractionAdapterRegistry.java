package io.github.legacygraph.extractors.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 抽取适配器注册表。
 * <p>
 * 自动发现所有 {@link ExtractionAdapter} 实现，根据项目资产选择合适的 Adapter。
 * ProjectScanner 通过此注册表获取适配器，不再硬编码每种技术栈的抽取细节。
 * </p>
 */
@Slf4j
@Component
public class ExtractionAdapterRegistry {

    private final List<ExtractionAdapter> adapters;

    /**
     * Spring 自动注入所有 ExtractionAdapter 实现。
     */
    public ExtractionAdapterRegistry(List<ExtractionAdapter> adapters) {
        this.adapters = new ArrayList<>(adapters);
        // 按优先级排序
        this.adapters.sort(Comparator.comparingInt(a -> a.capability().getPriority()));
        log.info("ExtractionAdapterRegistry initialized with {} adapters: {}",
                this.adapters.size(),
                this.adapters.stream().map(a -> a.capability().getName()).toList());
    }

    /**
     * 为指定资产选择所有匹配的适配器。
     * 
     * @param context 扫描上下文
     * @param asset   源资产
     * @return 所有匹配的适配器列表，无匹配时返回空列表
     */
    public List<ExtractionAdapter> selectAdapters(ScanContext context, SourceAsset asset) {
        List<ExtractionAdapter> matched = new ArrayList<>();
        for (ExtractionAdapter adapter : adapters) {
            if (adapter.supports(context, asset)) {
                matched.add(adapter);
            }
        }
        return matched;
    }

    /**
     * 获取所有已注册的适配器。
     */
    public List<ExtractionAdapter> getAllAdapters() {
        return Collections.unmodifiableList(adapters);
    }

    /**
     * 获取非 AI 增强型适配器（结构化的，优先执行）。
     */
    public List<ExtractionAdapter> getStructuralAdapters() {
        return adapters.stream()
                .filter(a -> !a.capability().isAiEnhanced())
                .toList();
    }

    /**
     * 获取 AI 增强型适配器（语义的，在结构化之后执行）。
     */
    public List<ExtractionAdapter> getAiEnhancedAdapters() {
        return adapters.stream()
                .filter(a -> a.capability().isAiEnhanced())
                .toList();
    }
}
