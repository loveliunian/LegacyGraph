package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.service.requirement.ImpactGraphWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 影响图谱查询控制器（G-19）。
 * <p>提供查询被某需求影响的节点列表的接口，
 * 基于 Neo4j 节点上由 {@link ImpactGraphWriter} 写入的 impactSource 属性过滤。</p>
 */
@RestController
@RequestMapping("/lg/graph")
@Tag(name = "影响图谱查询", description = "查询被需求影响的图谱节点")
public class ImpactGraphController {

    private final ImpactGraphWriter impactGraphWriter;

    public ImpactGraphController(ImpactGraphWriter impactGraphWriter) {
        this.impactGraphWriter = impactGraphWriter;
    }

    /**
     * 查询被某需求影响的节点列表。
     * <p>按 impactSource 属性过滤 Neo4j 节点，返回受影响节点的关键信息，
     * 包含影响层级、风险分数等，按影响深度升序排列。</p>
     *
     * @param requirementId 需求 ID
     * @return 受影响节点列表
     */
    @GetMapping("/impacted-nodes")
    @Operation(summary = "查询被需求影响的节点", description = "根据需求 ID 查询 Neo4j 中被该需求影响的节点列表，"
            + "返回节点 ID、名称、类型、影响深度、影响层级、风险分数等信息")
    public Result<List<Map<String, Object>>> getImpactedNodes(
            @Parameter(description = "需求 ID", required = true)
            @RequestParam String requirementId) {
        List<Map<String, Object>> nodes = impactGraphWriter.queryImpactedNodes(requirementId);
        return Result.success(nodes);
    }
}
