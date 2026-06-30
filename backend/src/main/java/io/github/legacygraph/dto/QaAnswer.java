package io.github.legacygraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 自然语言问答结果 — 对应 qa-answer 模板输出。
 *
 * <p>回答必须可审查：携带证据列表、相关节点和置信度（遵循"AI 不能直接作为事实源"的设计原则）。</p>
 */
@Data
public class QaAnswer {

    /** 自然语言回答 */
    private String answer;

    /** 回答置信度 0~1 */
    private Double confidence;

    /** 使用到的证据来源标识（节点 key 或片段编号） */
    private List<String> usedEvidence = new ArrayList<>();

    /** 与答案最相关的节点 key */
    private List<String> relatedNodeKeys = new ArrayList<>();

    /** 召回的证据明细（由服务端回填，便于前端展示来源与跳转） */
    private List<EvidenceItem> evidences = new ArrayList<>();

    @Data
    public static class EvidenceItem {
        /** 证据来源：GRAPH_NODE / DOC_CHUNK */
        private String sourceKind;
        /** 节点 key 或片段编号 */
        private String ref;
        /** 标题或名称 */
        private String title;
        /** 内容摘录 */
        private String excerpt;
        /** 来源路径（可选） */
        private String sourcePath;
        /** 召回相关性分数（可选） */
        private Double score;
    }
}
