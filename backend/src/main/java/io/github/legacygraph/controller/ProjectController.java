package io.github.legacygraph.controller;

import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.CreateProjectRequest;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.service.scan.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 项目管理控制器
 * 提供项目的创建、查询、删除等基本管理功能
 * 项目是LegacyGraph中最高级别的组织单元，一个项目对应一个遗留系统
 */
@RestController
@RequestMapping("/lg/projects")
@Tag(name = "项目管理", description = "项目的创建、分页查询、详情查询、删除等管理功能")
public class ProjectController {

    private final ProjectService projectService;

    /**
     * 构造函数注入
     * @param projectService 项目服务
     */
    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 创建新项目
     * @param request 创建项目请求参数
     * @return 新创建项目的ID
     */
    @PostMapping
    @Operation(summary = "创建新项目", description = "创建一个知识图谱分析项目，一个项目对应一个遗留系统")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "创建成功，返回项目ID"),
            @ApiResponse(responseCode = "400", description = "参数验证失败")
    })
    @Log(value = "创建项目", type = Log.OperationType.CREATE)
    public Result<String> create(@Valid @RequestBody CreateProjectRequest request) {
        Project project = projectService.createProject(request);
        return Result.success(project.getId());
    }

    /**
     * 分页查询项目列表
     * @param query 分页查询参数，包含页码和每页大小
     * @return 分页后的项目列表
     */
    @GetMapping
    @Operation(summary = "分页查询项目列表", description = "按创建时间倒序分页查询所有项目")
    @Log(value = "查询项目列表", type = Log.OperationType.QUERY)
    public Result<PageResult<Project>> list(PageQuery query) {
        PageResult<Project> result = projectService.listProjects(query);
        return Result.success(result);
    }

    /**
     * 获取项目详情
     * @param id 项目ID
     * @return 项目详情信息
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取项目详情", description = "根据项目ID获取项目的详细信息")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "404", description = "项目不存在")
    })
    @Log(value = "获取项目详情", type = Log.OperationType.QUERY)
    public Result<Project> get(@PathVariable String id) {
        Project project = projectService.getById(id);
        if (project == null) {
            return Result.error("项目不存在");
        }
        return Result.success(project);
    }

    /**
     * 删除项目
     * 根据项目ID删除整个项目，包括关联的所有数据
     * @param id 项目ID
     * @return 成功结果
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除项目", description = "根据项目ID删除整个项目，会级联删除关联的所有数据")
    @Log(value = "删除项目", type = Log.OperationType.DELETE)
    public Result<Void> delete(@PathVariable String id) {
        projectService.deleteById(id);
        return Result.success();
    }
}
