package io.github.legacygraph.extractors.adapter;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 扫描上下文 — 携带扫描所需的通用信息。
 */
@Data
@Builder
public class ScanContext {

    /** 项目ID */
    private String projectId;

    /** 版本ID */
    private String versionId;

    /** 代码基础目录 */
    private String baseDir;

    /** 后端代码目录 */
    private String backendDir;

    /** 前端代码目录 */
    private String frontendDir;

    /** 额外配置参数 */
    private Map<String, Object> config;

    public ScanContext() {}

    public ScanContext(String projectId, String versionId, String baseDir,
                       String backendDir, String frontendDir,
                       Map<String, Object> config) {
        this.projectId = projectId;
        this.versionId = versionId;
        this.baseDir = baseDir;
        this.backendDir = backendDir;
        this.frontendDir = frontendDir;
        this.config = config;
    }
}
