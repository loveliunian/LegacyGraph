package io.github.legacygraph.dto;

import lombok.Data;

/**
 * 创建扫描版本请求DTO
 */
@Data
public class CreateScanVersionRequest {

    private String projectId;
    private String versionNo;
    private String branchName;
    private String commitId;
    private String scanScope; // JSONB
}
