package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.dto.ProjectOverviewResponse;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.cache.CacheManager;
import io.github.legacygraph.service.scan.ProjectOverviewService;

/**
 * 项目概览服务集成测试（使用 H2 数据库）。
 * Mockito 无法 mock lambdaQuery() 链式调用，改用 @SpringBootTest 集成测试。
 */
@SpringBootTest
@Transactional
@Rollback
class ProjectOverviewServiceTest {

    @Autowired
    private ProjectOverviewService projectOverviewService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CodeRepoRepository codeRepoRepository;
    @Autowired
    private DbConnectionRepository dbConnectionRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private ScanVersionRepository scanVersionRepository;
    @Autowired
    private ReviewRecordRepository reviewRecordRepository;
    @Autowired
    private ProjectRepository projectRepository;

    private String projectId;

    @BeforeEach
    void setUp() {
        // 清除缓存避免跨测试污染
        if (cacheManager.getCache("project-overview") != null) {
            cacheManager.getCache("project-overview").clear();
        }
        codeRepoRepository.delete(new QueryWrapper<>());
        dbConnectionRepository.delete(new QueryWrapper<>());
        documentRepository.delete(new QueryWrapper<>());
        scanVersionRepository.delete(new QueryWrapper<>());
        reviewRecordRepository.delete(new QueryWrapper<>());

        projectId = "proj-overview-test";
        var p = new io.github.legacygraph.entity.Project();
        p.setId(projectId);
        p.setProjectCode("OVERVIEW");
        p.setProjectName("概览测试项目");
        p.setProjectType("LEGACY");
        p.setStatus("ACTIVE");
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        projectRepository.insert(p);
    }

    @Test
    void testGetOverview_EmptyProject() {
        ProjectOverviewResponse result = projectOverviewService.getOverview(projectId);

        assertThat(result).isNotNull();
        assertThat(result.getSourceStatus()).isNotNull();
        assertThat(result.getSourceStatus().getRepos().getConfigured()).isEqualTo(0);
        assertThat(result.getSourceStatus().getDatabases().getConfigured()).isEqualTo(0);
        assertThat(result.getSourceStatus().getDocuments().getUploaded()).isEqualTo(0);
    }

    @Test
    void testGetOverview_WithRepos() {
        CodeRepo repo = new CodeRepo();
        repo.setProjectId(projectId);
        repo.setRepoName("test-repo");
        repo.setRepoType("GIT");
        repo.setGitUrl("https://github.com/test/repo");
        repo.setStatus("READY");
        repo.setCreatedAt(LocalDateTime.now());
        repo.setUpdatedAt(LocalDateTime.now());
        codeRepoRepository.insert(repo);

        ProjectOverviewResponse result = projectOverviewService.getOverview(projectId);

        assertThat(result.getSourceStatus().getRepos().getConfigured()).isEqualTo(1);
        assertThat(result.getSourceStatus().getRepos().getScanned()).isEqualTo(1);
    }

    @Test
    void testGetOverview_WithFailedRepo() {
        CodeRepo repo = new CodeRepo();
        repo.setProjectId(projectId);
        repo.setRepoName("fail-repo");
        repo.setRepoType("GIT");
        repo.setGitUrl("https://github.com/test/fail");
        repo.setStatus("PULL_FAILED");
        repo.setCreatedAt(LocalDateTime.now());
        repo.setUpdatedAt(LocalDateTime.now());
        codeRepoRepository.insert(repo);

        ProjectOverviewResponse result = projectOverviewService.getOverview(projectId);

        assertThat(result.getSourceStatus().getRepos().getConfigured()).isEqualTo(1);
        assertThat(result.getSourceStatus().getRepos().getFailed()).isEqualTo(1);
    }
}
