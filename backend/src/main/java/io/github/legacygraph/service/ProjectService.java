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
 * 管理知识图谱分析项目，项目是LegacyGraph中最高级别的组织单元
 * 一个项目对应一个需要分析的遗留系统
 */
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;

    /**
     * 构造函数注入
     * @param projectRepository 项目数据访问层
     */
    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    /**
     * 分页查询项目列表
     * 按创建时间倒序排列
     * @param query 分页查询参数，包含页码和每页大小
     * @return 分页后的项目列表
     */
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

    /**
     * 根据ID获取项目详情
     * @param id 项目ID
     * @return 项目详情，不存在返回null
     */
    public Project getById(String id) {
        return projectRepository.selectById(id);
    }

    /**
     * 创建新项目
     * 根据请求参数创建项目，生成唯一ID，设置初始状态
     * @param request 创建项目请求
     * @return 已创建的项目
     */
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

    /**
     * 根据ID删除项目
     * @param id 项目ID
     * @throws BusinessException 如果项目不存在抛出异常
     */
    public void deleteById(String id) {
        Project project = projectRepository.selectById(id);
        if (project == null) {
            throw new BusinessException(404, "项目不存在");
        }
        projectRepository.deleteById(id);
    }
}
