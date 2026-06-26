package io.github.legacygraph.controller;

import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.CreateProjectRequest;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
    public Result<String> create(@RequestBody CreateProjectRequest request) {
        try {
            Project project = projectService.createProject(request);
            return Result.success(project.getId());
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping
    @Operation(summary = "分页查询项目列表")
    public Result<PageResult<Project>> list(PageQuery query) {
        PageResult<Project> result = projectService.pageList(query);
        return Result.success(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取项目详情")
    public Result<Project> get(@PathVariable String id) {
        Project project = projectService.getById(id);
        if (project == null) {
            return Result.error("项目不存在");
        }
        return Result.success(project);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除项目")
    public Result<Void> delete(@PathVariable String id) {
        projectService.deleteById(id);
        return Result.success();
    }
}
