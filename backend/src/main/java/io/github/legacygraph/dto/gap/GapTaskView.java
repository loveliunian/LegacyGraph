package io.github.legacygraph.dto.gap;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * GapTask 视图 DTO — 用于 API 返回。
 * 从 GapTask 实体转换，将 JSONB 字符串解析为 List。
 */
@Data
@Builder
public class GapTaskView {

    private String id;

    private String gapType;

    private String gapKey;

    private String title;

    private String description;

    private String severity;

    private String status;

    private String subjectType;

    private String subjectKey;

    private List<String> relatedClaimIds;

    private List<String> relatedNodeIds;

    private List<String> evidenceIds;

    private String suggestedAction;

    private BigDecimal priorityScore;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
