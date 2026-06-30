package io.github.legacygraph.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReviewConfirmRequest {

    @NotBlank(message = "审核目标类型不能为空")
    private String targetType;

    @NotBlank(message = "审核目标ID不能为空")
    private String targetId;

    @NotBlank(message = "审核状态不能为空")
    private String reviewStatus;

    /** 扫描版本ID（可选，不提供时自动查询项目最新版本） */
    private String versionId;

    @Size(max = 500, message = "审核意见长度不能超过500个字符")
    private String comment;
}
