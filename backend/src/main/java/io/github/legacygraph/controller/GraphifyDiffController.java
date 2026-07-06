package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.graphify.GraphifyDiff;
import io.github.legacygraph.graphify.GraphifyDiffService;
import io.github.legacygraph.graphify.GraphifyImportSnapshot;
import io.github.legacygraph.graphify.GraphifyImportSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Graphify 版本差异对比 API。
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/graphify/diff")
@RequiredArgsConstructor
public class GraphifyDiffController {

    private final GraphifyDiffService diffService;
    private final GraphifyImportSnapshotService snapshotService;

    /**
     * 对比两个扫描版本的 Graphify 导入差异。
     *
     * @param projectId    项目ID
     * @param oldVersionId 旧版本ID
     * @param newVersionId 新版本ID
     * @return 差异结果
     */
    @GetMapping("/{oldVersionId}/{newVersionId}")
    public Result<GraphifyDiff> diff(
            @PathVariable String projectId,
            @PathVariable String oldVersionId,
            @PathVariable String newVersionId) {

        log.info("Graphify diff request: project={}, old={}, new={}", projectId, oldVersionId, newVersionId);

        GraphifyImportSnapshot oldSnapshot = snapshotService.buildSnapshot(projectId, oldVersionId);
        GraphifyImportSnapshot newSnapshot = snapshotService.buildSnapshot(projectId, newVersionId);

        GraphifyDiff diff = diffService.diff(oldSnapshot, newSnapshot);
        return Result.ok(diff);
    }
}
