package io.github.legacygraph.integration.graphify;

import lombok.*;

import java.util.List;

/**
 * Graphify JSON 兼容性检查报告。
 * <p>
 * 包含 schema 版本、可导入性判断、字段统计和问题列表。
 * </p>
 */
@Value
@Builder
@Data
@AllArgsConstructor
public class GraphifyCompatibilityReport {
    
    /** Schema 版本 */
    GraphifySchemaVersion schemaVersion;
    
    /** 是否可以导入 */
    boolean canImport;
    
    /** 节点数量 */
    int nodeCount;
    
    /** 边数量 */
    int edgeCount;
    
    /** 超边数量 */
    int hyperedgeCount;
    
    /** 不支持的顶层字段 */
    List<String> unsupportedTopLevelFields;
    
    /** 缺失的必填字段 */
    List<String> missingRequiredFields;
    
    /** 警告信息 */
    List<String> warnings;
}
