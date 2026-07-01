package io.github.legacygraph.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReviewCreateRequest {

    private String targetType;

    private String targetId;

    private String targetName;

    private String graphType;

    private Double confidence;

    private Integer evidenceCount;

    private String priority;

    /** 扫描版本ID（可选，不提供时自动查询项目最新版本） */
    private String versionId;

    @Size(max = 500, message = "审核意见长度不能超过500个字符")
    private String comment;
}
