package io.github.legacygraph.service.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * G-03 / S2-T5: DocumentParsingRouter — 文档解析策略路由。
 * <p>
 * 根据文档 MIME 类型和大小，自动选择最合适的解析策略：
 * <ol>
 *   <li>{@link FastParsingStrategy} — 纯文本/Markdown/源码 → 直接读取</li>
 *   <li>{@link LayoutParsingStrategy} — PDF/Word/HTML → 版面结构解析</li>
 *   <li>{@link OcrParsingStrategy} — 扫描件/图片 → OCR 文字识别</li>
 * </ol>
 * </p>
 * <p>
 * 通过 Feature Flag {@code legacygraph.parse.three-layer.enabled=false}（默认关闭）控制是否启用三层解析。
 * 关闭时所有文档走 {@link FastParsingStrategy}（向后兼容）。
 * </p>
 * <p>
 * S2-T5 新增：
 * <ul>
 *   <li>策略优先级外置配置：{@code legacygraph.parse.strategy-priority}（默认 OCR>LAYOUT>FAST）</li>
 *   <li>超时回退链：策略解析超时后自动降级到下一优先级策略</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class DocumentParsingRouter {

    private final List<DocumentParsingStrategy> strategies;
    private final FastParsingStrategy fastFallback;

    @Value("${legacygraph.parse.three-layer.enabled:false}")
    private boolean threeLayerEnabled;

    /** S2-T5: 策略优先级配置（逗号分隔，如 "OCR,LAYOUT,FAST"），高优先级在前 */
    @Value("${legacygraph.parse.strategy-priority:OCR,LAYOUT,FAST}")
    private String strategyPriorityConfig;

    /** S2-T5: 单策略解析超时（毫秒），超时后降级到下一策略 */
    @Value("${legacygraph.parse.strategy-timeout-ms:30000}")
    private long strategyTimeoutMs;

    @Autowired
    public DocumentParsingRouter(List<DocumentParsingStrategy> strategies,
                                  FastParsingStrategy fastFallback) {
        this.strategies = strategies != null ? strategies : new ArrayList<>();
        this.fastFallback = fastFallback;
        log.info("DocumentParsingRouter initialized with {} strategies (three-layer enabled={})",
                this.strategies.size(), threeLayerEnabled);
    }

    /**
     * 路由到最合适的解析策略。
     *
     * @param mimeType 文档 MIME 类型
     * @param fileSize 文件大小（字节，-1 表示未知）
     * @return 选中的解析策略
     */
    public DocumentParsingStrategy route(String mimeType, long fileSize) {
        if (!threeLayerEnabled) {
            return fastFallback;
        }
        // S2-T5: 按配置的优先级遍历策略
        List<DocumentParsingStrategy> orderedStrategies = getOrderedStrategies();
        for (DocumentParsingStrategy strategy : orderedStrategies) {
            if (strategy.supports(mimeType, fileSize)) {
                log.debug("Routed to strategy={} for mimeType={}, fileSize={}",
                        strategy.strategyName(), mimeType, fileSize);
                return strategy;
            }
        }
        log.debug("No specific strategy matched, falling back to FAST for mimeType={}", mimeType);
        return fastFallback;
    }

    /**
     * 使用路由选中的策略解析文档。
     * S2-T5: 增加超时回退链 — 如果选中策略解析超时，自动降级到下一优先级策略。
     */
    public String parse(String content, String mimeType, long fileSize) {
        if (!threeLayerEnabled) {
            return fastFallback.parse(content, mimeType);
        }

        // S2-T5: 获取按优先级排序的候选策略链
        List<DocumentParsingStrategy> candidates = new ArrayList<>();
        DocumentParsingStrategy primary = route(mimeType, fileSize);
        candidates.add(primary);

        // 构建回退链：除主策略外，按优先级添加其他支持或兜底策略
        List<DocumentParsingStrategy> ordered = getOrderedStrategies();
        for (DocumentParsingStrategy s : ordered) {
            if (!candidates.contains(s)) {
                candidates.add(s);
            }
        }
        // 最终兜底始终是 FastParsingStrategy
        if (!candidates.contains(fastFallback)) {
            candidates.add(fastFallback);
        }

        // S2-T5: 依次尝试候选策略，超时或异常时降级
        for (int i = 0; i < candidates.size(); i++) {
            DocumentParsingStrategy strategy = candidates.get(i);
            try {
                String result = parseWithTimeout(strategy, content, mimeType, strategyTimeoutMs);
                if (result != null && !result.isBlank()) {
                    if (i > 0) {
                        log.info("Fallback to strategy={} succeeded after {} failed attempts",
                                strategy.strategyName(), i);
                    }
                    return result;
                }
                log.warn("Strategy {} returned empty result, trying next fallback", strategy.strategyName());
            } catch (Exception e) {
                log.warn("Strategy {} failed ({}), falling back to next strategy",
                        strategy.strategyName(), e.getMessage());
                if (i == candidates.size() - 1) {
                    // 最后一个策略也失败，返回空字符串而非抛异常
                    log.error("All parsing strategies exhausted, returning empty result");
                    return "";
                }
            }
        }
        return "";
    }

    /**
     * S2-T5: 带超时的策略解析。
     * 使用虚拟线程（或线程池）执行解析，超时后抛出异常触发回退。
     */
    private String parseWithTimeout(DocumentParsingStrategy strategy, String content,
                                     String mimeType, long timeoutMs) {
        if (timeoutMs <= 0) {
            return strategy.parse(content, mimeType);
        }
        try {
            var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            var future = executor.submit(() -> strategy.parse(content, mimeType));
            String result = future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            executor.shutdownNow();
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("Parse timeout after " + timeoutMs + "ms (strategy=" +
                    strategy.strategyName() + ")", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException("Parse failed (strategy=" + strategy.strategyName() + "): " +
                    (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parse interrupted (strategy=" + strategy.strategyName() + ")", e);
        }
    }

    /**
     * S2-T5: 按配置的优先级排序策略列表。
     */
    private List<DocumentParsingStrategy> getOrderedStrategies() {
        if (strategyPriorityConfig == null || strategyPriorityConfig.isBlank()) {
            return strategies;
        }
        List<String> priorityOrder = Arrays.asList(strategyPriorityConfig.toUpperCase().split(","));
        List<DocumentParsingStrategy> ordered = new ArrayList<>();
        for (String name : priorityOrder) {
            for (DocumentParsingStrategy s : strategies) {
                if (s.strategyName().equalsIgnoreCase(name.trim()) && !ordered.contains(s)) {
                    ordered.add(s);
                }
            }
        }
        // 添加未在配置中列出的策略
        for (DocumentParsingStrategy s : strategies) {
            if (!ordered.contains(s)) {
                ordered.add(s);
            }
        }
        return ordered;
    }

    /**
     * 是否启用三层解析。
     */
    public boolean isThreeLayerEnabled() {
        return threeLayerEnabled;
    }

    /**
     * S2-T5: 获取当前策略优先级配置。
     */
    public String getStrategyPriorityConfig() {
        return strategyPriorityConfig;
    }

    /**
     * S2-T5: 获取策略超时配置（毫秒）。
     */
    public long getStrategyTimeoutMs() {
        return strategyTimeoutMs;
    }
}
