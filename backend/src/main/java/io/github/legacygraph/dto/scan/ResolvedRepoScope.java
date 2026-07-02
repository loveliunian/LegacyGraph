package io.github.legacygraph.dto.scan;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 解析后的仓库扫描范围。
 */
@Data
@Builder
public class ResolvedRepoScope {

    private String repoId;

    /** 代码仓库在本地的基础路径 */
    private String baseDir;

    /** 后端子目录（相对于 baseDir） */
    private String backendDir;

    /** 前端子目录（相对于 baseDir） */
    private String frontendDir;

    /** 包含的文件模式 */
    private List<String> includePatterns;

    /** 排除的文件模式 */
    private List<String> excludePatterns;
}
