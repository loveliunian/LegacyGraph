package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.ReviewConfirmRequest;
import io.github.legacygraph.service.GraphValidatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/lg/validation")
@Tag(name = "图谱验证", description = "验证图谱正确性，更新置信度，生成验证报告")
public class ValidationController {

    private final GraphValidatorService graphValidatorService;

    public ValidationController(GraphValidatorService graphValidatorService) {
        this.graphValidatorService = graphValidatorService;
    }

    @GetMapping("/report/{versionId}")
    @Operation(summary = "获取验证报告")
    public Result<GraphValidatorService.ValidationReport> getReport(@PathVariable String versionId) {
        GraphValidatorService.ValidationReport report = graphValidatorService.getValidationReport(versionId);
        return Result.success(report);
    }

    @PostMapping("/update-confidence/{versionId}")
    @Operation(summary = "根据测试结果更新置信度")
    public Result<Void> updateConfidence(@PathVariable String versionId) {
        graphValidatorService.updateConfidenceByTestResults(versionId);
        return Result.success();
    }

    @PostMapping("/confirm")
    @Operation(summary = "人工确认节点或关系")
    public Result<Void> confirm(@RequestBody ReviewConfirmRequest request) {
        String targetType = request.getTargetType();
        String targetId = request.getTargetId();
        String reviewStatus = request.getReviewStatus();

        if ("NODE".equals(targetType)) {
            if ("CONFIRMED".equals(reviewStatus)) {
                graphValidatorService.confirmNode(targetId, "currentUser");
            } else {
                graphValidatorService.rejectNode(targetId, "currentUser");
            }
        } else if ("EDGE".equals(targetType)) {
            if ("CONFIRMED".equals(reviewStatus)) {
                graphValidatorService.confirmEdge(targetId, "currentUser");
            } else {
                graphValidatorService.rejectEdge(targetId, "currentUser");
            }
        }

        return Result.success();
    }
}
