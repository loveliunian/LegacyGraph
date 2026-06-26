package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.GenerateTestCasesRequest;
import io.github.legacygraph.dto.StartTestRunRequest;
import io.github.legacygraph.service.TestCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lg")
@Tag(name = "测试用例", description = "生成测试用例、执行测试")
public class TestCaseController {

    private final TestCaseService testCaseService;

    public TestCaseController(TestCaseService testCaseService) {
        this.testCaseService = testCaseService;
    }

    @PostMapping("/test-cases/generate")
    @Operation(summary = "生成测试用例")
    public Result<String> generate(@RequestBody GenerateTestCasesRequest request) {
        try {
            String executionId = testCaseService.generateTestCases(request);
            return Result.success(executionId);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/test-runs/start")
    @Operation(summary = "执行测试用例")
    public Result<String> start(@RequestBody StartTestRunRequest request) {
        try {
            String executionId = testCaseService.startTestRun(request);
            return Result.success(executionId);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
