package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.dto.CreateProjectRequest;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.exception.BusinessException;
import io.github.legacygraph.repository.ProjectRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 项目服务
 */
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public PageResult<Project> listProjects(PageQuery query) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Project::getCreatedAt);

        Page<Project> page = projectRepository.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        return PageResult.of(
                page.getRecords(),
                page.getTotal(),
                query.getPageNum(),
                query.getPageSize()
        );
    }

    public Project getById(String id) {
        return projectRepository.selectById(id);
    }

    public Project createProject(CreateProjectRequest request) {
        Project project = new Project();
        project.setId(UUID.randomUUID().toString());
        project.setProjectCode(request.getProjectCode());
        project.setProjectName(request.getProjectName());
        project.setDescription(request.getDescription());
        project.setProjectType(request.getProjectType() != null ? request.getProjectType() : "LEGACY");
        project.setStatus("INIT");
        project.setOwner(request.getOwner());
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());

        projectRepository.insert(project);
        return project;
    }

    public void deleteById(String id) {
        Project project = projectRepository.selectById(id);
        if (project == null) {
            throw new BusinessException(404, "项目不存在");
        }
        projectRepository.deleteById(id);
    }
}
