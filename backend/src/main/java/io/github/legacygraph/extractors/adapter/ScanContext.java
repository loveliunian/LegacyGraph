package io.github.legacygraph.extractors.adapter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 扫描上下文 — 携带扫描所需的通用信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
}
