package io.github.legacygraph.controller;

import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.CreateProjectRequest;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/lg/projects")
@Tag(name = "项目管理", description = "项目的创建、查询、删除")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @Operation(summary = "创建项目")
    @Log(value = "创建项目", type = Log.OperationType.CREATE)
    public Result<String> create(@Valid @RequestBody CreateProjectRequest request) {
        Project project = projectService.createProject(request);
        return Result.success(project.getId());
    }

    @GetMapping
    @Operation(summary = "分页查询项目列表")
    @Log(value = "查询项目列表", type = Log.OperationType.QUERY)
    public Result<PageResult<Project>> list(PageQuery query) {
        PageResult<Project> result = projectService.listProjects(query);
        return Result.success(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取项目详情")
    @Log(value = "获取项目详情", type = Log.OperationType.QUERY)
    public Result<Project> get(@PathVariable String id) {
        Project project = projectService.getById(id);
        if (project == null) {
            return Result.error("项目不存在");
        }
        return Result.success(project);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除项目")
    @Log(value = "删除项目", type = Log.OperationType.DELETE)
    public Result<Void> delete(@PathVariable String id) {
        projectService.deleteById(id);
        return Result.success();
    }
}
