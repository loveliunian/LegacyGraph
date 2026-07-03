package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.EvidenceConflict;
import io.github.legacygraph.service.graph.EvidenceConflictService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/lg/evidence-conflicts")
@RequiredArgsConstructor
public class EvidenceConflictController {

    private final EvidenceConflictService service;

    @GetMapping
    public Result<List<EvidenceConflict>> list(@RequestParam String projectId,
                                               @RequestParam(defaultValue = "false") boolean includeResolved) {
        return Result.ok(service.list(projectId, includeResolved));
    }

    @PostMapping("/{id}/resolve")
    public Result<EvidenceConflict> resolve(@PathVariable String id,
                                            @RequestBody ResolveRequest request) {
        if (request.getResolution() == null || request.getResolution().isBlank()) {
            return Result.badRequest("请选择处理方式");
        }
        try {
            return Result.ok(service.resolve(id, request.getResolution()));
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage());
        }
    }

    @Data
    public static class ResolveRequest {
        private String resolution;
    }
}
