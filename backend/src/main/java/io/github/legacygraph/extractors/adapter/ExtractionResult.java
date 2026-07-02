package io.github.legacygraph.extractors.adapter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 抽取结果 — 一个 Adapter 完成扫描后返回的结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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


}
