package io.github.legacygraph.controller;

import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.LlmProvider;
import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.service.system.LlmProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * LLM 提供商管理控制器
 * 管理 LLM 提供商的增删改查、启用/禁用、切换默认
 */
@RestController
@RequestMapping("/llm/providers")
@RequiredArgsConstructor
@Tag(name = "LLM Provider API", description = "LLM 提供商配置管理，支持多模型切换")
public class LlmProviderController {

    private final LlmProviderService llmProviderService;
    private final LlmGateway llmGateway;

    /**
     * 获取所有 LLM 提供商列表
     */
    @GetMapping
    @Operation(summary = "获取所有 LLM 提供商")
    public Result<List<LlmProvider>> listAll() {
        return Result.ok(llmProviderService.listAll());
    }

    /**
     * 获取指定提供商详情
     */
    @GetMapping("/{providerCode}")
    @Operation(summary = "获取指定提供商详情")
    public Result<LlmProvider> getByCode(
            @Parameter(description = "提供商代码", required = true)
            @PathVariable String providerCode) {
        LlmProvider provider = llmProviderService.getByCode(providerCode);
        if (provider == null) {
            return Result.error("提供商不存在: " + providerCode);
        }
        return Result.ok(provider);
    }

    /**
     * 获取当前默认提供商
     */
    @GetMapping("/default")
    @Operation(summary = "获取当前默认提供商")
    public Result<LlmProvider> getDefault() {
        LlmProvider provider = llmProviderService.getActiveDefault();
        if (provider == null) {
            return Result.error("没有可用的 LLM 提供商");
        }
        return Result.ok(provider);
    }

    /**
     * 保存或更新提供商
     */
    @Log(value = "创建LLM提供商", type = Log.OperationType.CREATE)
    @PostMapping
    @Operation(summary = "新增或更新 LLM 提供商")
    public Result<LlmProvider> save(
            @Parameter(description = "提供商配置", required = true)
            @RequestBody LlmProvider provider) {
        return Result.ok(llmProviderService.save(provider));
    }

    /**
     * 切换默认提供商
     */
    @PutMapping("/{providerCode}/set-default")
    @Operation(summary = "切换为默认提供商")
    public Result<Void> setDefault(
            @Parameter(description = "提供商代码", required = true)
            @PathVariable String providerCode) {
        llmProviderService.setDefault(providerCode);
        llmGateway.clearCache(); // 清除缓存使切换立即生效
        return Result.ok();
    }

    /**
     * 启用/禁用提供商
     */
    @PutMapping("/{providerCode}/toggle-active")
    @Operation(summary = "启用或禁用提供商")
    public Result<Void> toggleActive(
            @Parameter(description = "提供商代码", required = true)
            @PathVariable String providerCode,
            @Parameter(description = "是否启用", required = true)
            @RequestParam boolean active) {
        llmProviderService.toggleActive(providerCode, active);
        llmGateway.clearCache(); // 清除缓存使状态变更立即生效
        return Result.ok();
    }

    /**
     * 删除提供商
     */
    @DeleteMapping("/{providerCode}")
    @Operation(summary = "删除提供商")
    public Result<Void> delete(
            @Parameter(description = "提供商代码", required = true)
            @PathVariable String providerCode) {
        llmProviderService.delete(providerCode);
        llmGateway.clearCache(); // 清除缓存避免误用已删除的提供商
        return Result.ok();
    }

    /**
     * 测试提供商连通性 — 使用当前保存的 API Key 发起一次轻量调用。
     * <p>向量模型（provider_code 含 "embedding"）调用 embed 接口验证；
     * 其他模型调用 chat 接口验证。</p>
     */
    @PostMapping("/{providerCode}/test")
    @Operation(summary = "测试提供商连通性", description = "用当前 API Key 发起轻量调用，区分 chat / embedding 两类模型")
    public Result<Map<String, Object>> testProvider(
            @Parameter(description = "提供商代码", required = true)
            @PathVariable String providerCode) {
        LlmProvider provider = llmProviderService.getByCode(providerCode);
        if (provider == null) {
            return Result.error("提供商不存在: " + providerCode);
        }
        Map<String, Object> result = llmProviderService.testProvider(provider);
        return Result.ok(result);
    }
}
