package io.github.legacygraph.controller;

import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.requirement.ContractGenerationRequest;
import io.github.legacygraph.dto.requirement.ContractSpec;
import io.github.legacygraph.service.requirement.ContractGeneratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 契约生成控制器（G-17）。
 * <p>从 {@code RequirementController} 拆出，承担"需求 → OpenAPI / TypeScript 契约"链路。
 * 接口前缀 {@code /lg/projects/{projectId}}，与项目内其它资源对齐。</p>
 *
 * <p>迁移说明：原路径 {@code POST /lg/projects/{projectId}/requirements/{requirementId}/contract}
 * 已被新路径 {@code POST /lg/projects/{projectId}/contracts/requirements/{requirementId}} 取代。
 * 旧路径由 {@link ContractLegacyRedirectController} 以 308 重定向兜底，
 * 老客户端无需立刻切换。</p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/contracts")
@Tag(name = "G-17 Contract", description = "需求 → OpenAPI / TypeScript 契约生成")
public class ContractController {

    /** OpenAPI 3.0 输出 */
    private static final String FORMAT_OPENAPI = "OPENAPI";
    /** TypeScript 接口定义输出 */
    private static final String FORMAT_TYPESCRIPT = "TYPESCRIPT";

    private final ContractGeneratorService contractGeneratorService;

    public ContractController(ContractGeneratorService contractGeneratorService) {
        this.contractGeneratorService = contractGeneratorService;
    }

    @Log(value = "生成API契约", type = Log.OperationType.QUERY)
    @PostMapping("/requirements/{requirementId}")
    @Operation(summary = "从需求生成API契约",
            description = "根据需求条目自动生成 OpenAPI 3.0 YAML 或 TypeScript 接口定义契约。"
                    + "request.format 取值 OPENAPI（默认）/ TYPESCRIPT。")
    public Result<String> generateContract(
            @PathVariable String projectId,
            @PathVariable String requirementId,
            @RequestBody(required = false) ContractGenerationRequest request) {
        ContractGenerationRequest req = request != null ? request : new ContractGenerationRequest();
        ContractSpec spec = contractGeneratorService.generateSpec(projectId, requirementId);

        if (req.getEndpointBase() != null && !req.getEndpointBase().isBlank()) {
            spec.setBasePath(req.getEndpointBase());
        }
        String format = req.getFormat() != null ? req.getFormat().toUpperCase() : FORMAT_OPENAPI;
        String contract;
        if (FORMAT_TYPESCRIPT.equals(format)) {
            contract = contractGeneratorService.toTypeScript(spec);
        } else {
            contract = contractGeneratorService.toOpenApiYaml(spec);
        }
        log.info("Contract generated: projectId={}, requirementId={}, format={}, endpoints={}",
                projectId, requirementId, format, spec.getEndpoints().size());
        return Result.success(contract);
    }
}