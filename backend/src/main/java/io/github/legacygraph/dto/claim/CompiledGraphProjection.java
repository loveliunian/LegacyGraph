package io.github.legacygraph.dto.claim;

import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 编译后的图谱投影 — KnowledgeCompiler 输出结果。
 */
@Data
@Builder
public class CompiledGraphProjection {

    /** 生成的节点声明 */
    @Builder.Default
    private List<GraphNodeClaim> nodeClaims = new ArrayList<>();

    /** 生成的边声明 */
    @Builder.Default
    private List<GraphEdgeClaim> edgeClaims = new ArrayList<>();

    /** 编译问题列表 */
    @Builder.Default
    private List<ClaimCompileIssue> issues = new ArrayList<>();

    /** 跳过的 Claim 数量（不满足编译条件） */
    private int skippedCount;

    /** 成功编译的 Claim 数量 */
    private int compiledCount;

    /** 是否 dry-run */
    private boolean dryRun;
}
