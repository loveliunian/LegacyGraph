package io.github.legacygraph.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建扫描版本请求DTO
 */
@Data
public class CreateScanVersionRequest {

    @NotBlank(message = "项目ID不能为空")
    private String projectId;

    @Size(max = 50, message = "版本号长度不能超过50个字符")
    private String versionNo;

    private String branchName;
    private String commitId;
    private String scanScope; // JSONB
}
