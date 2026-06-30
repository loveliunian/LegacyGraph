package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.dto.CreateProjectRequest;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.exception.BusinessException;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final CodeRepoRepository codeRepoRepository;

    /**
     * 构造函数注入
     * @param projectRepository 项目数据访问层
     * @param codeRepoRepository 代码仓库数据访问层
     */
    public ProjectService(ProjectRepository projectRepository,
                          CodeRepoRepository codeRepoRepository) {
        this.projectRepository = projectRepository;
        this.codeRepoRepository = codeRepoRepository;
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
     * 根据请求参数创建项目，生成唯一ID，设置初始状态。
     * 如果提供了 Git 仓库地址，默认创建一个全栈类型的代码仓库配置。
     * @param request 创建项目请求
     * @return 已创建的项目
     */
    @Transactional
    public Project createProject(CreateProjectRequest request) {
        Project project = new Project();
        project.setId(UUID.randomUUID().toString());
        project.setProjectCode(request.getProjectCode());
        project.setProjectName(request.getProjectName());
        project.setDescription(request.getDescription());
        project.setProjectType(request.getProjectType() != null ? request.getProjectType() : "LEGACY");
        project.setRepoUrl(request.getRepoUrl());
        project.setDefaultBranch(request.getDefaultBranch());
        project.setStatus("INIT");
        project.setOwner(request.getOwner());
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());

        projectRepository.insert(project);

        // 如果创建项目时填写了 Git 仓库地址，自动创建代码仓库配置
        String repoUrl = request.getRepoUrl();
        if (repoUrl != null && !repoUrl.isBlank()) {
            String branch = request.getDefaultBranch();
            if (branch == null || branch.isBlank()) {
                branch = "main";
            }

            CodeRepo codeRepo = new CodeRepo();
            codeRepo.setId(UUID.randomUUID().toString());
            codeRepo.setProjectId(project.getId());
            codeRepo.setRepoName(extractRepoName(repoUrl));
            codeRepo.setRepoType("FULLSTACK"); // 默认全栈类型
            codeRepo.setGitUrl(repoUrl.trim());
            codeRepo.setBranchName(branch);
            codeRepo.setStatus("PENDING");
            codeRepo.setCreatedAt(LocalDateTime.now());
            codeRepo.setUpdatedAt(LocalDateTime.now());

            codeRepoRepository.insert(codeRepo);
        }

        return project;
    }

    /**
     * 从 Git URL 中提取仓库名称
     * 例如: https://github.com/loveliunian/LegacyGraph.git → LegacyGraph
     */
    private String extractRepoName(String gitUrl) {
        String url = gitUrl.trim();
        // 去掉末尾的 .git
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }
        // 取最后一个路径段作为仓库名
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < url.length() - 1) {
            return url.substring(lastSlash + 1);
        }
        return url;
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
