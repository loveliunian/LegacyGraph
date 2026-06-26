package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.dto.CreateProjectRequest;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class ProjectService extends ServiceImpl<ProjectRepository, Project> {

    /**
     * 创建项目
     */
    public Project createProject(CreateProjectRequest request) {
        // 检查项目编码是否已存在
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Project::getProjectCode, request.getProjectCode());
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("项目编码已存在: " + request.getProjectCode());
        }

        Project project = new Project();
        project.setProjectCode(request.getProjectCode());
        project.setProjectName(request.getProjectName());
        project.setDescription(request.getDescription());
        project.setProjectType(request.getProjectType() != null ? request.getProjectType() : "LEGACY");
        project.setRepoUrl(request.getRepoUrl());
        project.setDefaultBranch(request.getDefaultBranch());
        project.setOwner(request.getOwner());
        project.setStatus("ACTIVE");
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());

        save(project);
        return project;
    }

    /**
     * 分页查询项目列表
     */
    public PageResult<Project> pageList(PageQuery query) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Project::getStatus, "ACTIVE");
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w
                    .like(Project::getProjectName, query.getKeyword())
                    .or().like(Project::getProjectCode, query.getKeyword())
                    .or().like(Project::getDescription, query.getKeyword())
            );
        }
        wrapper.orderByDesc(Project::getCreatedAt);

        Page<Project> page = page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /**
     * 根据ID获取项目
     */
    public Project getById(String id) {
        return getById(id);
    }

    /**
     * 删除项目（逻辑删除）
     */
    public void deleteById(String id) {
        removeById(id);
    }
}
