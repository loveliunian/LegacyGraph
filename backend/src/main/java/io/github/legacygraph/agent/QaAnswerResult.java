package io.github.legacygraph.agent;

import io.github.legacygraph.dto.EvidenceItem;

import java.util.List;

/**
 * 同步问答结果 — 评测 / 门禁场景使用，由 {@link EnhancedQaAgent#answer} 返回。
 *
 * @param answer     自然语言回答（可能为空，表示拒答或出错）
 * @param evidences  引用的证据列表
 * @param confidence 置信度 0~1
 * @param error      是否发生错误（超时 / 异常 / 拒答失败）
 */
public record QaAnswerResult(String answer, List<EvidenceItem> evidences, double confidence, boolean error) {
}
