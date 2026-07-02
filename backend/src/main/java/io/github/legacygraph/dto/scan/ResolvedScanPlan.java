package io.github.legacygraph.dto.scan;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析后的扫描计划 — scanScope 的强类型表示。
 * 由 {@code ScanScopeResolver} 从 scanScope JSON 解析生成，
 * 供整个扫描流程使用，避免各处重复解析。
 */
@Data
@Builder
public class ResolvedScanPlan {

    private String projectId;

    private String versionId;

    /** 仓库范围列表 */
    private List<ResolvedRepoScope> repos;

    /** 数据库范围列表 */
    private List<ResolvedDbScope> databases;

    /** 文档范围列表 */
    private List<ResolvedDocScope> documents;

    /** 扫描类型集合 */
    private Set<String> scanTypes;

    /** 是否启用 AI */
    private boolean aiEnabled;

    /** 是否增量扫描 */
    private boolean incremental;

    /** 最大文件数 */
    private int maxFiles;

    /** 最大文档数 */
    private int maxDocs;

    /** 最大数据库表数 */
    private int maxDbTables;

    /** 原始 scanScope（保留未解析的扩展字段） */
    private Map<String, Object> rawScope;
}
