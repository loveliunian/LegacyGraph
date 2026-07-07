package io.github.legacygraph.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 影响子图 — ChangeTask 的图谱定位结果（增强版2）。
 * <p>
 * 由 {@code ImpactSubgraphService} 从目标节点邻域（或 FeatureSlice）提取，
 * 供 ChangeImpactAgent/PatchPlanAgent 作为上下文；同时用于补丁落盘前的范围校验
 * （见 doc §PatchPlan 输出契约 的范围校验）。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImpactSubgraph {

    /** 变更目标节点ID */
    private String targetNodeId;

    /** 目标节点展示名 */
    private String targetName;

    /** 目标节点类型 */
    private String targetNodeType;

    /** 邻域节点ID列表（含目标本身） */
    private List<String> nodeIds;

    /** 邻域边ID列表 */
    private List<String> edgeIds;

    /** 受影响的源文件路径（范围校验白名单） */
    private List<String> impactedFiles;

    /** 多跳路径的节点 key 序列（每条路径一条，单跳场景可空） */
    private List<List<String>> pathNodeKeys;

    /** 供 LLM 使用的依赖摘要文本 */
    private String dependencySummary;


}
