package io.github.legacygraph.governance;

/**
 * 角色权限定义。
 */
public enum Role {
    /** 图谱管理员：可以运行导入、重试作业、回滚作业、修改 Graphify 配置 */
    GRAPHIFY_ADMIN,
    
    /** 图谱审核者：可以审核候选边，但不能运行导入和查看原始代码片段 */
    GRAPH_REVIEWER,
    
    /** 证据查看者：可以查看原始 evidence 内容和未脱敏 source path */
    GRAPH_EVIDENCE_VIEWER,
    
    /** 只读查看者：只能查看脱敏后的图谱信息 */
    GRAPH_VIEWER
}
