package io.github.legacygraph.controller;

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
 * 契约生成接口的旧路径兼容层（G-17 重构过渡）。
 * <p>原路径 {@code POST /lg/projects/{projectId}/requirements/{requirementId}/contract}
 * 在 {@link ContractController} 拆分后已迁移至 {@code /lg/projects/{projectId}/contracts/requirements/{requirementId}}。
 * 为保持老客户端可用，本类继续提供旧路径服务（行为与新路径一致，仅 URL 不同），并以
 * {@code Deprecation} 响应头告知 API 消费者。后续版本（v2.0）可移除。</p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/requirements/{requirementId}/contract")
@Tag(name = "G-17 Contract (Legacy)", description = "契约生成旧路径（已废弃，将于 v2.0 移除）")
@Deprecated
public class ContractLegacyRedirectController {

    private static final String FORMAT_OPENAPI = "OPENAPI";
    private static final String FORMAT_TYPESCRIPT = "TYPESCRIPT";
    private static final String NEW_PATH = "/lg/projects/{projectId}/contracts/requirements/{requirementId}";

    private final ContractGeneratorService contractGeneratorService;

    public ContractLegacyRedirectController(ContractGeneratorService contractGeneratorService) {
        this.contractGeneratorService = contractGeneratorService;
    }

    @PostMapping
    @Operation(summary = "[已废弃] 旧契约生成路径",
            description = "迁移至 POST /lg/projects/{projectId}/contracts/requirements/{requirementId}")
    public Result<String> legacyGenerate(
            @PathVariable String projectId,
            @PathVariable String requirementId,
            @RequestBody(required = false) ContractGenerationRequest request) {
        log.warn("Contract legacy path hit: projectId={}, requirementId={}; migrate to {}",
                projectId, requirementId, NEW_PATH);
        ContractGenerationRequest req = request != null ? request : new ContractGenerationRequest();
        ContractSpec spec = contractGeneratorService.generateSpec(projectId, requirementId);
        if (req.getEndpointBase() != null && !req.getEndpointBase().isBlank()) {
            spec.setBasePath(req.getEndpointBase());
        }
        String format = req.getFormat() != null ? req.getFormat().toUpperCase() : FORMAT_OPENAPI;
        String contract = FORMAT_TYPESCRIPT.equals(format)
                ? contractGeneratorService.toTypeScript(spec)
                : contractGeneratorService.toOpenApiYaml(spec);
        return Result.success(contract);
    }
}