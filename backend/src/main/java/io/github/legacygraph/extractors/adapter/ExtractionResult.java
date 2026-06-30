package io.github.legacygraph.extractors.adapter;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 抽取结果 — 一个 Adapter 完成扫描后返回的结果。
 */
@Data
@Builder
public class ExtractionResult {

    /** 处理的资产数 */
    private int processedAssets;

    /** 抽取的节点数 */
    private int nodeCount;

    /** 抽取的边数 */
    private int edgeCount;

    /** 创建的证据数 */
    private int evidenceCount;

    /** 结果汇总信息 */
    private String summary;

    /** 附加数据（供后续步骤使用） */
    private List<Object> extractedData;

    public ExtractionResult() {
        this.extractedData = new ArrayList<>();
    }

    public ExtractionResult(int processedAssets, int nodeCount, int edgeCount,
                            int evidenceCount, String summary,
                            List<Object> extractedData) {
        this.processedAssets = processedAssets;
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
        this.evidenceCount = evidenceCount;
        this.summary = summary;
        this.extractedData = extractedData != null ? extractedData : new ArrayList<>();
    }
}
