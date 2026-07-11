package io.github.legacygraph.service.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * G-03: DocumentParsingRouter — 文档解析策略路由。
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
 */
@Slf4j
@Service
public class DocumentParsingRouter {

    private final List<DocumentParsingStrategy> strategies;
    private final FastParsingStrategy fastFallback;

    @Value("${legacygraph.parse.three-layer.enabled:false}")
    private boolean threeLayerEnabled;

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
        for (DocumentParsingStrategy strategy : strategies) {
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
     */
    public String parse(String content, String mimeType, long fileSize) {
        DocumentParsingStrategy strategy = route(mimeType, fileSize);
        return strategy.parse(content, mimeType);
    }

    /**
     * 是否启用三层解析。
     */
    public boolean isThreeLayerEnabled() {
        return threeLayerEnabled;
    }
}
