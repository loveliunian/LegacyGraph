package io.github.legacygraph.dto.claim;

import lombok.Builder;
import lombok.Data;

/**
 * Claim 关联证据引用 DTO — 用于记录 Claim 所使用的具体证据片段。
 */
@Data
@Builder
public class ClaimEvidenceRef {

    /** 证据 ID */
    private String evidenceId;

    /** 证据来源类型 */
    private String sourceType;

    /** 证据来源 URI */
    private String sourceUri;

    /** 起始行号 */
    private Integer lineStart;

    /** 结束行号 */
    private Integer lineEnd;

    /** 证据摘录 */
    private String excerpt;
}
