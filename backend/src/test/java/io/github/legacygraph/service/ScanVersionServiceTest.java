package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.CreateScanVersionRequest;
import io.github.legacygraph.dto.ScanProgressResponse;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.service.graph.GraphCacheInvalidator;
import io.github.legacygraph.service.scan.ScanVersionService;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;

@ExtendWith(MockitoExtension.class)
class ScanVersionServiceTest {

    @Mock private ScanTaskRepository scanTaskRepository;
    @Mock private ScanVersionRepository scanVersionRepository;
    @Mock private CacheService cacheService;
    @Mock private GraphCacheInvalidator graphCacheInvalidator;
    @Mock private Neo4jGraphDao neo4jGraphDao;
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
    @Mock private KnowledgeClaimRepository knowledgeClaimRepository;
    @Mock private GapTaskRepository gapTaskRepository;
    @Mock private MigrationRiskRepository migrationRiskRepository;

    @InjectMocks
    private ScanVersionService scanVersionService;

    private String testProjectId = "test-project-1";
    private CreateScanVersionRequest createRequest;

    @BeforeEach
    void setUp() {
        createRequest = new CreateScanVersionRequest();
        createRequest.setVersionNo("v1.0.0");
        createRequest.setBranchName("main");
        createRequest.setCommitId("abc123");
        createRequest.setScanScope("src/");
        lenient().when(cacheService.get(anyString(), any())).thenReturn(null);
    }

    @Test
    void testCreateScanVersion_Success() {
        ScanVersion result = scanVersionService.createScanVersion(testProjectId, createRequest);

        assertNotNull(result);
        assertEquals(testProjectId, result.getProjectId());
        assertEquals("v1.0.0", result.getVersionNo());
        assertEquals("main", result.getBranchName());
        assertEquals("abc123", result.getCommitId());
        assertEquals("src/", result.getScanScope());
        assertEquals("CREATED", result.getScanStatus());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
    }

    @Test
    void testGetScanProgress_VersionNotFound() {
        when(scanVersionRepository.selectById("nonexistent")).thenReturn(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> scanVersionService.getScanProgress("nonexistent"));

        assertTrue(exception.getMessage().contains("扫描版本不存在"));
    }

    @Test
    void testGetScanProgress_NoTasks() {
        ScanVersion version = new ScanVersion();
        version.setId("version-1");
        version.setScanStatus("CREATED");

        when(scanVersionRepository.selectById("version-1")).thenReturn(version);
        when(scanTaskRepository.selectList(any())).thenReturn(Collections.emptyList());

        ScanProgressResponse response = scanVersionService.getScanProgress("version-1");

        assertNotNull(response);
        assertEquals("version-1", response.getVersionId());
        assertEquals("CREATED", response.getStatus());
        assertEquals(0, response.getProgress());
        // 所有阶段显示为 PENDING
        assertTrue(response.getTasks().size() > 0);
        assertTrue(response.getTasks().stream().allMatch(t -> "PENDING".equals(t.getStatus())));
    }

    @Test
    void testGetScanProgress_WithTasks_MixedStatus() {
        ScanVersion version = new ScanVersion();
        version.setId("version-1");
        version.setScanStatus("RUNNING");

        List<ScanTask> tasks = new ArrayList<>();

        ScanTask task1 = new ScanTask();
        task1.setId("task-1");
        task1.setVersionId("version-1");
        task1.setTaskType("DB_DISCOVERY");
        task1.setTaskStatus("SUCCESS");
        task1.setStartedAt(java.time.LocalDateTime.now().minusSeconds(10));
        tasks.add(task1);

        ScanTask task2 = new ScanTask();
        task2.setId("task-2");
        task2.setVersionId("version-1");
        task2.setTaskType("PATH_DISCOVERY");
        task2.setTaskStatus("RUNNING");
        task2.setStartedAt(java.time.LocalDateTime.now().minusSeconds(5));
        tasks.add(task2);

        when(scanVersionRepository.selectById("version-1")).thenReturn(version);
        when(scanTaskRepository.selectList(any())).thenReturn(tasks);

        ScanProgressResponse response = scanVersionService.getScanProgress("version-1");

        assertNotNull(response);
        assertEquals(1, response.getProgress() > 0 ? 1 : 0); // at least some progress
        assertTrue(response.getTasks().size() > 0);
    }

    @Test
    void testGetScanProgress_AllCompleted() {
        ScanVersion version = new ScanVersion();
        version.setId("version-1");
        version.setScanStatus("SUCCESS");

        List<ScanTask> tasks = new ArrayList<>();
        // 使用注册的阶段 taskType
        String[] phaseTypes = {"DB_DISCOVERY", "PATH_DISCOVERY", "DOC_DISCOVERY",
                "ADAPTER_SCAN", "DATABASE_SCAN", "GRAPH_BUILD", "AI_ORCHESTRATION"};
        for (int i = 0; i < phaseTypes.length; i++) {
            ScanTask task = new ScanTask();
            task.setId("task-" + i);
            task.setVersionId("version-1");
            task.setTaskType(phaseTypes[i]);
            task.setTaskStatus("SUCCESS");
            task.setStartedAt(java.time.LocalDateTime.now().minusSeconds(60 + i * 10));
            tasks.add(task);
        }

        when(scanVersionRepository.selectById("version-1")).thenReturn(version);
        when(scanTaskRepository.selectList(any())).thenReturn(tasks);

        ScanProgressResponse response = scanVersionService.getScanProgress("version-1");

        assertEquals(100, response.getProgress());
    }

    @Test
    void testGetScanProgress_IncludesTaskTimelineFields() {
        ScanVersion version = new ScanVersion();
        version.setId("version-timeline");
        version.setScanStatus("RUNNING");

        LocalDateTime startedAt = LocalDateTime.now().minusSeconds(30);
        LocalDateTime finishedAt = LocalDateTime.now().minusSeconds(10);
        ScanTask task = new ScanTask();
        task.setId("task-1");
        task.setVersionId("version-timeline");
        task.setTaskType("DB_DISCOVERY");
        task.setTaskStatus("SUCCESS");
        task.setStartedAt(startedAt);
        task.setFinishedAt(finishedAt);

        when(scanVersionRepository.selectById("version-timeline")).thenReturn(version);
        when(scanTaskRepository.selectList(any())).thenReturn(List.of(task));

        ScanProgressResponse response = scanVersionService.getScanProgress("version-timeline");

        ScanProgressResponse.TaskProgress dbDiscovery = response.getTasks().stream()
                .filter(tp -> "DB_DISCOVERY".equals(tp.getTaskType()))
                .findFirst()
                .orElseThrow();
        assertEquals(startedAt, dbDiscovery.getStartedAt());
        assertEquals(finishedAt, dbDiscovery.getFinishedAt());
    }

    @Test
    void testUpdateScanStatus_VersionNotFound() {
        when(scanVersionRepository.selectById("version-1")).thenReturn(null);

        assertDoesNotThrow(() -> scanVersionService.updateScanStatus("version-1", "RUNNING"));
    }

    @Test
    void testUpdateScanStatus_SetRunning() {
        ScanVersion version = new ScanVersion();
        version.setId("version-1");

        when(scanVersionRepository.selectById("version-1")).thenReturn(version);

        scanVersionService.updateScanStatus("version-1", "RUNNING");

        assertEquals("RUNNING", version.getScanStatus());
        assertNotNull(version.getStartedAt());
        assertNotNull(version.getUpdatedAt());
    }

    @Test
    void testUpdateScanStatus_SetSuccess() {
        ScanVersion version = new ScanVersion();
        version.setId("version-1");

        when(scanVersionRepository.selectById("version-1")).thenReturn(version);

        scanVersionService.updateScanStatus("version-1", "SUCCESS");

        assertEquals("SUCCESS", version.getScanStatus());
        assertNotNull(version.getFinishedAt());
        assertNotNull(version.getUpdatedAt());
    }

    @Test
    void testUpdateScanStatus_SetFailed() {
        ScanVersion version = new ScanVersion();
        version.setId("version-1");

        when(scanVersionRepository.selectById("version-1")).thenReturn(version);

        scanVersionService.updateScanStatus("version-1", "FAILED");

        assertEquals("FAILED", version.getScanStatus());
        assertNotNull(version.getFinishedAt());
        assertNotNull(version.getUpdatedAt());
    }
}
