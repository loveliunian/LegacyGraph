package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.service.graph.GraphDiffReadModel;
import io.github.legacygraph.service.graph.GraphDiffReadModel.DiffResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 图谱版本差异 Controller — 跨版本节点/边对比
 */
@RestController
@RequestMapping("/lg/graph/diff")
@RequiredArgsConstructor
@Tag(name = "图谱差异对比", description = "跨版本图谱节点/边差异")
public class GraphDiffController {

    private final GraphDiffReadModel graphDiffReadModel;

    @GetMapping
    @Operation(summary = "对比两个版本的图谱差异")
    public Result<DiffResult> diff(
            @RequestParam String projectId,
            @RequestParam String versionA,
            @RequestParam String versionB) {
        DiffResult result = graphDiffReadModel.diffVersions(projectId, versionA, versionB);
        return Result.success(result);
    }
}
