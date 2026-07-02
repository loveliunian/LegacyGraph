package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.dto.graph.EvidenceRecord;
import io.github.legacygraph.dto.graph.GraphWriteIntent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 抽取结果 — 一个 Adapter 完成扫描后返回的结果。
 * 扩展字段支持影子 Claim/Evidence 产出。
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
    @Builder.Default
    private List<Object> extractedData = new ArrayList<>();

    /** 证据记录列表（影子产出） */
    @Builder.Default
    private List<EvidenceRecord> evidenceRecords = new ArrayList<>();

    /** Claim 草稿列表（影子产出） */
    @Builder.Default
    private List<KnowledgeClaimDraft> claimDrafts = new ArrayList<>();

    /** 图谱写入意图（影子产出） */
    private GraphWriteIntent graphWriteIntent;

    /** 警告信息列表 */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
