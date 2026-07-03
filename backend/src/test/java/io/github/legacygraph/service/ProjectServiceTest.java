package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.CreateProjectRequest;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.exception.BusinessException;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.service.graph.GraphCacheInvalidator;
import io.github.legacygraph.service.scan.ProjectService;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private CodeRepoRepository codeRepoRepository;
    @Mock private DbConnectionRepository dbConnectionRepository;
    @Mock private CacheService cacheService;
    @Mock private Neo4jGraphDao neo4jGraphDao;
    @Mock private ScanVersionRepository scanVersionRepository;
    @Mock private ScanTaskRepository scanTaskRepository;
    @Mock private GraphNodeRepository graphNodeRepository;
    @Mock private GraphEdgeRepository graphEdgeRepository;
    @Mock private FactRepository factRepository;
    @Mock private EvidenceRepository evidenceRepository;
    @Mock private NodeEvidenceRepository nodeEvidenceRepository;
    @Mock private EdgeEvidenceRepository edgeEvidenceRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocChunkRepository docChunkRepository;
    @Mock private TestCaseRepository testCaseRepository;
    @Mock private TestRunRepository testRunRepository;
    @Mock private TestResultRepository testResultRepository;
    @Mock private TestAssertionRepository testAssertionRepository;
    @Mock private RuntimeTraceRepository runtimeTraceRepository;
    @Mock private ReviewRecordRepository reviewRecordRepository;
    @Mock private ReportRepository reportRepository;
    @Mock private VectorDocumentRepository vectorDocumentRepository;
    @Mock private PromptRunRepository promptRunRepository;
    @Mock private DomainOntologyTermRepository domainOntologyTermRepository;
    @Mock private DomainOntologyRelationRepository domainOntologyRelationRepository;
    @Mock private KnowledgeClaimRepository knowledgeClaimRepository;
    @Mock private GapTaskRepository gapTaskRepository;
    @Mock private MigrationRiskRepository migrationRiskRepository;
    @Mock private AgentRunRepository agentRunRepository;
    @Mock private ChangeTaskRepository changeTaskRepository;
    @Mock private PrTaskRepository prTaskRepository;
    @Mock private PatchFileRepository patchFileRepository;
    @Mock private ValidationGateRepository validationGateRepository;
    @Mock private GraphCacheInvalidator graphCacheInvalidator;

    @InjectMocks
    private ProjectService projectService;

    private CreateProjectRequest createRequest;

    @BeforeEach
    void setUp() {
        createRequest = new CreateProjectRequest();
        createRequest.setProjectCode("test-project");
        createRequest.setProjectName("测试项目");
        createRequest.setDescription("这是一个测试项目");
        createRequest.setOwner("test-user");
    }

    @Test
    void testListProjects_Empty() {
        Page<Project> mockPage = new Page<>(1, 10);
        mockPage.setRecords(Collections.emptyList());
        mockPage.setTotal(0);

        when(projectRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageQuery pageQuery = new PageQuery();
        pageQuery.setPageNum(1);
        pageQuery.setPageSize(10);
        PageResult<Project> result = projectService.listProjects(pageQuery);

        assertNotNull(result);
        assertEquals(0, result.getTotal());
        assertTrue(result.getList().isEmpty());
    }

    @Test
    void testListProjects_WithData() {
        Project project = new Project();
        project.setId("project-1");
        project.setProjectName("Test Project");

        List<Project> records = List.of(project);
        Page<Project> mockPage = new Page<>(1, 10);
        mockPage.setRecords(records);
        mockPage.setTotal(1);

        when(projectRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageQuery pageQuery = new PageQuery();
        pageQuery.setPageNum(1);
        pageQuery.setPageSize(10);
        PageResult<Project> result = projectService.listProjects(pageQuery);

        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals("Test Project", result.getList().get(0).getProjectName());
    }

    @Test
    void testGetById_Found() {
        Project project = new Project();
        project.setId("project-1");
        project.setProjectName("Test Project");

        when(projectRepository.selectById("project-1")).thenReturn(project);

        Project result = projectService.getById("project-1");

        assertNotNull(result);
        assertEquals("project-1", result.getId());
        assertEquals("Test Project", result.getProjectName());
    }

    @Test
    void testGetById_NotFound() {
        when(projectRepository.selectById("nonexistent")).thenReturn(null);

        Project result = projectService.getById("nonexistent");

        assertNull(result);
    }

    @Test
    void testCreateProject_Success() {
        Project result = projectService.createProject(createRequest);

        assertNotNull(result);
        assertNotNull(result.getId());
        assertEquals("test-project", result.getProjectCode());
        assertEquals("测试项目", result.getProjectName());
        assertEquals("这是一个测试项目", result.getDescription());
        assertEquals("test-user", result.getOwner());
        assertEquals("LEGACY", result.getProjectType());
        assertEquals("INIT", result.getStatus());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());

        verify(projectRepository, times(1)).insert(any(Project.class));
    }

    @Test
    void testCreateProject_CustomType() {
        createRequest.setProjectType("MODERN");

        Project result = projectService.createProject(createRequest);

        assertNotNull(result);
        assertEquals("MODERN", result.getProjectType());
    }

    @Test
    void testDeleteById_Success() {
        Project project = new Project();
        project.setId("project-1");

        when(projectRepository.selectById("project-1")).thenReturn(project);
        when(scanVersionRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        when(projectRepository.deleteById("project-1")).thenReturn(1);

        assertDoesNotThrow(() -> projectService.deleteById("project-1"));

        verify(projectRepository, times(1)).deleteById("project-1");
    }

    @Test
    void testDeleteById_NotFound() {
        when(projectRepository.selectById("nonexistent")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> projectService.deleteById("nonexistent"));

        assertEquals(404, exception.getCode());
        assertEquals("项目不存在", exception.getMessage());
    }
}
