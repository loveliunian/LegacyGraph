package io.github.legacygraph.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 通用节点抽取结果 - 用于业务域/流程/对象/规则/角色等 Extractor 的统一输出
 */
@Data
@Builder
public class NodeExtractionResult {
    /** 节点类型（NodeType 枚举名） */
    private String nodeType;
    /** 节点唯一标识 */
    private String nodeKey;
    /** 显示名称 */
    private String displayName;
    /** 描述 */
    private String description;
    /** 来源路径 */
    private String sourcePath;
    /** 来源类型（CODE_AST / DOC_AI 等） */
    private String sourceType;
    /** 置信度 (0-1) */
    private double confidence;
    /** 额外属性 */
    private Map<String, Object> properties;
}
