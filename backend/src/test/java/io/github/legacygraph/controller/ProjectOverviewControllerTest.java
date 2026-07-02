package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.DbConnectionRepository;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 项目概览控制器集成测试
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class ProjectOverviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private CodeRepoRepository codeRepoRepository;

    @Autowired
    private DbConnectionRepository dbConnectionRepository;

    @Autowired
    private DocumentRepository documentRepository;

    private final String testProjectId = "test-project-overview";

    @BeforeEach
    void setUp() {
        // 清除缓存避免跨测试污染
        if (cacheManager.getCache("project-overview") != null) {
            cacheManager.getCache("project-overview").clear();
        }
        // 清理相关表数据
        codeRepoRepository.delete(new QueryWrapper<>());
        dbConnectionRepository.delete(new QueryWrapper<>());
        documentRepository.delete(new QueryWrapper<>());
        projectRepository.delete(new QueryWrapper<>());

        // 创建测试项目
        Project p = new Project();
        p.setId(testProjectId);
        p.setProjectCode("OVERVIEW-TEST");
        p.setProjectName("Overview Test Project");
        p.setRepoUrl("https://github.com/test/overview");
        p.setOwner("admin");
        p.setStatus("ACTIVE");
        projectRepository.insert(p);
    }

    @Test
    void testGetOverview_Success() throws Exception {
        // 添加一些源码数据以验证 sourceStatus
        CodeRepo repo = new CodeRepo();
        repo.setProjectId(testProjectId);
        repo.setRepoName("test-repo");
        repo.setRepoType("GIT");
        repo.setStatus("READY");
        repo.setGitUrl("https://github.com/test/repo.git");
        codeRepoRepository.insert(repo);

        mockMvc.perform(get("/lg/projects/{projectId}/overview", testProjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.sourceStatus").exists())
                .andExpect(jsonPath("$.data.graphStats").exists());
    }

    @Test
    void testGetOverview_WithSources() throws Exception {
        // 创建代码仓库
        CodeRepo repo = new CodeRepo();
        repo.setProjectId(testProjectId);
        repo.setRepoName("test-repo");
        repo.setRepoType("GIT");
        repo.setStatus("READY");
        repo.setGitUrl("https://github.com/test/repo.git");
        codeRepoRepository.insert(repo);

        // 创建数据库连接
        DbConnection db = new DbConnection();
        db.setProjectId(testProjectId);
        db.setConnectionName("test-db");
        db.setDbType("POSTGRESQL");
        db.setHost("localhost");
        db.setPort(5432);
        db.setDatabaseName("testdb");
        db.setStatus("READY");
        dbConnectionRepository.insert(db);

        // 创建文档
        Document doc = new Document();
        doc.setProjectId(testProjectId);
        doc.setDocName("test-doc.md");
        doc.setDocType("GENERAL");
        doc.setParseStatus("PARSED");
        documentRepository.insert(doc);

        mockMvc.perform(get("/lg/projects/{projectId}/overview", testProjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sourceStatus.repos.configured").value(1))
                .andExpect(jsonPath("$.data.sourceStatus.databases.configured").value(1))
                .andExpect(jsonPath("$.data.sourceStatus.documents.uploaded").value(1));
    }

    @Test
    void testGetOverview_ProjectNotFound() throws Exception {
        // 请求一个不存在的 projectId，服务仍返回空数据（兼容）
        mockMvc.perform(get("/lg/projects/{projectId}/overview", "nonexistent-project"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    void testGetOverview_EmptyProject() throws Exception {
        // 项目存在但没有任何源码配置
        mockMvc.perform(get("/lg/projects/{projectId}/overview", testProjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sourceStatus.repos.configured").value(0))
                .andExpect(jsonPath("$.data.sourceStatus.databases.configured").value(0))
                .andExpect(jsonPath("$.data.sourceStatus.documents.uploaded").value(0));
    }
}
