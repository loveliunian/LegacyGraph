package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.service.GraphQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/lg/graph")
@Tag(name = "图谱查询", description = "查询调用链、影响范围、业务视图、功能视图")
public class GraphQueryController {

    private final GraphQueryService graphQueryService;

    public GraphQueryController(GraphQueryService graphQueryService) {
        this.graphQueryService = graphQueryService;
    }

    @GetMapping("/api-chain")
    @Operation(summary = "查询接口完整调用链")
    public Result<List<Map<String, Object>>> getApiChain(
            String versionId,
            String api) {
        try {
            List<Map<String, Object>> result = graphQueryService.getApiCallChain(versionId, api);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/table-impact")
    @Operation(summary = "查询表影响范围")
    public Result<List<Map<String, Object>>> getTableImpact(
            String versionId,
            String tableName) {
        try {
            List<Map<String, Object>> result = graphQueryService.getTableImpact(versionId, tableName);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/feature-view")
    @Operation(summary = "查询功能图谱视图")
    public Result<Map<String, Object>> getFeatureView(
            String versionId,
            String module) {
        try {
            Map<String, Object> result = graphQueryService.getFeatureView(versionId, module);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/business-view")
    @Operation(summary = "查询业务图谱视图")
    public Result<Map<String, Object>> getBusinessView(
            String versionId,
            String domain) {
        try {
            Map<String, Object> result = graphQueryService.getBusinessView(versionId, domain);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
