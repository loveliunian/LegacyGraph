package io.github.legacygraph.controller;

import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.PromptTemplate;
import io.github.legacygraph.service.system.PromptTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 提示词模板管理控制器 — LLM 提示词统一管理。
 */
@RestController
@RequestMapping("/lg/admin/prompts")
@Tag(name = "提示词模板管理", description = "LLM 提示词模板的增删改查、激活/停用")
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    public PromptTemplateController(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    @GetMapping("/list")
    @Operation(summary = "分页查询提示词模板列表")
    public Result<PageResult<PromptTemplate>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String status) {
        PageResult<PromptTemplate> result = promptTemplateService.list(pageNum, pageSize, keyword, scene, status);
        return Result.success(result);
    }

    @GetMapping("/active")
    @Operation(summary = "获取所有激活的模板")
    public Result<List<PromptTemplate>> listActive() {
        List<PromptTemplate> templates = promptTemplateService.listActive();
        return Result.success(templates);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取模板详情")
    public Result<PromptTemplate> getById(
            @Parameter(description = "模板ID", required = true)
            @PathVariable Long id) {
        PromptTemplate template = promptTemplateService.getById(id);
        if (template == null) {
            return Result.error("模板不存在");
        }
        return Result.success(template);
    }

    @PostMapping
    @Operation(summary = "创建提示词模板")
    public Result<PromptTemplate> create(@RequestBody PromptTemplate template) {
        if (template.getTemplateCode() == null || template.getTemplateCode().isBlank()) {
            return Result.error("模板编码不能为空");
        }
        // 检查编码是否已存在
        PromptTemplate existing = promptTemplateService.getActiveByCode(template.getTemplateCode());
        if (existing != null) {
            return Result.error("模板编码已存在: " + template.getTemplateCode());
        }
        PromptTemplate created = promptTemplateService.create(template);
        return Result.success(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新提示词模板（创建新版本）")
    public Result<PromptTemplate> update(
            @Parameter(description = "模板ID", required = true)
            @PathVariable Long id,
            @RequestBody PromptTemplate template) {
        template.setId(id);
        PromptTemplate updated = promptTemplateService.update(template);
        if (updated == null) {
            return Result.error("模板不存在");
        }
        return Result.success(updated);
    }

    @PutMapping("/{id}/toggle")
    @Operation(summary = "切换模板激活状态")
    public Result<Void> toggleActive(
            @Parameter(description = "模板ID", required = true)
            @PathVariable Long id) {
        boolean result = promptTemplateService.toggleActive(id);
        if (!result) {
            return Result.error("模板不存在");
        }
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除提示词模板")
    public Result<Void> delete(
            @Parameter(description = "模板ID", required = true)
            @PathVariable Long id) {
        boolean result = promptTemplateService.delete(id);
        if (!result) {
            return Result.error("删除失败，模板不存在");
        }
        return Result.success();
    }

    @PostMapping("/cache/refresh")
    @Operation(summary = "刷新模板缓存")
    public Result<Void> refreshCache() {
        promptTemplateService.evictCache();
        return Result.success();
    }
}
