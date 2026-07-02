package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.ChangeImpactAgent;
import io.github.legacygraph.agent.adapter.MigrationAgentAdapter;
import io.github.legacygraph.agent.adapter.RefactorAgentAdapter;
import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.PatchPlan;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.repository.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChangeTaskServiceTest {

    @Mock private ChangeTaskRepository changeTaskRepository;
    @Mock private PatchFileRepository patchFileRepository;
    @Mock private ValidationGateRepository validationGateRepository;
    @Mock private ReviewRecordRepository reviewRecordRepository;
    @Mock private ImpactSubgraphService impactSubgraphService;
    @Mock private ChangeImpactAgent changeImpactAgent;
    @Mock private RefactorAgentAdapter refactorAgentAdapter;
    @Mock private MigrationAgentAdapter migrationAgentAdapter;
    @Mock private io.github.legacygraph.agent.PatchPlanAgent patchPlanAgent;
    @Mock private ValidationGateRunner validationGateRunner;
    @Mock private PrOrchestrator prOrchestrator;
    @Mock private TransactionTemplate transactionTemplate;

    private ChangeTaskService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Mock TransactionTemplate to directly run the callback (bypass actual TX in unit test)
        lenient().doAnswer(invocation -> {
            var consumer = invocation.getArgument(0, java.util.function.Consumer.class);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().doAnswer(invocation -> {
            var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());

        service = new ChangeTaskService(changeTaskRepository, patchFileRepository,
                validationGateRepository, reviewRecordRepository, impactSubgraphService,
                changeImpactAgent, refactorAgentAdapter, migrationAgentAdapter,
                patchPlanAgent, new PatchPlanValidator(), validationGateRunner,
                prOrchestrator, objectMapper, transactionTemplate);
    }

    @Test
    void createTask_setsOpenStatus() {
        ChangeTask task = service.createTask("p1", "v1", "REFACTOR", "拆分大类", null);
        assertEquals("OPEN", task.getStatus());
        assertEquals("REFACTOR", task.getTaskType());
        verify(changeTaskRepository).insert(any(ChangeTask.class));
    }

    @Test
    void listTasks_blankProjectIdReturnsEmptyAndDoesNotQuery() {
        assertTrue(service.listTasks(" ").isEmpty());
        verify(changeTaskRepository, never()).selectList(any());
    }

    @Test
    void listTasks_returnsRepositoryTasksForProject() {
        ChangeTask task = new ChangeTask();
        task.setId("chg-1");
        task.setProjectId("p1");
        when(changeTaskRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(task));

        List<ChangeTask> result = service.listTasks("p1");

        assertEquals(List.of(task), result);
        verify(changeTaskRepository).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void generatePatch_outOfScope_marksReviewPending() {
        ChangeTask task = new ChangeTask();
        task.setId("chg-1");
        task.setProjectId("p1");
        task.setVersionId("v1");
        task.setTaskType("REFACTOR");
        task.setTitle("拆分大类");
        // impacted subgraph 只允许 src/A.java
        ImpactSubgraph sg = ImpactSubgraph.builder().targetNodeId("n1")
                .impactedFiles(List.of("src/A.java")).build();
        try {
            task.setImpactedSubgraph(objectMapper.writeValueAsString(sg));
        } catch (Exception e) {
            fail(e);
        }
        when(changeTaskRepository.selectById("chg-1")).thenReturn(task);

        // adapter 返回一个越界文件补丁
        PatchPlan plan = PatchPlan.builder()
                .taskId("chg-1").taskType("REFACTOR").riskLevel("MEDIUM")
                .generatedBy("RefactorAgent")
                .patches(List.of(PatchPlan.Patch.builder()
                        .filePath("src/OutOfScope.java").changeType("MODIFY")
                        .patchText("@@ -1 +1 @@\n-a\n+b").evidenceIds(List.of("evd-1")).build()))
                .build();
        when(refactorAgentAdapter.toPatchPlan(eq("chg-1"), eq("p1"), any(), any(), any(), any(), anyList()))
                .thenReturn(plan);

        ChangeTaskService.PatchGenRequest req = new ChangeTaskService.PatchGenRequest();
        req.setTarget("BigClass");
        req.setFilePath("src/OutOfScope.java");
        req.setEvidenceIds(List.of("evd-1"));

        service.generatePatch("chg-1", req);

        ArgumentCaptor<ChangeTask> captor = ArgumentCaptor.forClass(ChangeTask.class);
        verify(changeTaskRepository).updateById(captor.capture());
        assertEquals("REVIEW_PENDING", captor.getValue().getStatus());
        verify(reviewRecordRepository).insert(any(io.github.legacygraph.entity.ReviewRecord.class));
    }

    @Test
    void generatePatch_bugfix_routesToPatchPlanAgent() {
        ChangeTask task = new ChangeTask();
        task.setId("chg-2");
        task.setProjectId("p1");
        task.setTaskType("BUGFIX");
        task.setTitle("修复空指针");
        ImpactSubgraph sg = ImpactSubgraph.builder().targetNodeId("n1")
                .impactedFiles(List.of("src/A.java")).build();
        try {
            task.setImpactedSubgraph(objectMapper.writeValueAsString(sg));
        } catch (Exception e) {
            fail(e);
        }
        when(changeTaskRepository.selectById("chg-2")).thenReturn(task);

        PatchPlan plan = PatchPlan.builder()
                .taskId("chg-2").taskType("BUGFIX").riskLevel("LOW")
                .generatedBy("PatchPlanAgent")
                .patches(List.of(PatchPlan.Patch.builder()
                        .filePath("src/A.java").changeType("MODIFY")
                        .patchText("@@ -1 +1 @@\n-a\n+b").evidenceIds(List.of("evd-1")).build()))
                .build();
        when(patchPlanAgent.generate(eq("p1"), eq("chg-2"), any(), any(), any(), any(), any()))
                .thenReturn(plan);

        ChangeTaskService.PatchGenRequest req = new ChangeTaskService.PatchGenRequest();
        req.setEvidenceIds(List.of("evd-1"));
        PatchPlan result = service.generatePatch("chg-2", req);

        assertEquals("BUGFIX", result.getTaskType());
        verify(patchPlanAgent).generate(eq("p1"), eq("chg-2"), any(), any(), any(), any(), any());
        verify(patchFileRepository).insert(any(io.github.legacygraph.entity.PatchFile.class));
    }

    @Test
    void generatePatch_nullPlan_throws() {
        ChangeTask task = new ChangeTask();
        task.setId("chg-2b");
        task.setProjectId("p1");
        task.setTaskType("BUGFIX");
        when(changeTaskRepository.selectById("chg-2b")).thenReturn(task);
        when(patchPlanAgent.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn(null);

        ChangeTaskService.PatchGenRequest req = new ChangeTaskService.PatchGenRequest();
        assertThrows(IllegalStateException.class, () -> service.generatePatch("chg-2b", req));
    }

    @Test
    void runValidation_allPass_setsValidationPassed() {
        ChangeTask task = new ChangeTask();
        task.setId("chg-3");
        task.setProjectId("p1");
        task.setVersionId("v1");
        when(changeTaskRepository.selectById("chg-3")).thenReturn(task);
        when(validationGateRunner.runAll(eq("chg-3"), any())).thenReturn(true);

        ChangeTask result = service.runValidation("chg-3", List.of(), null, "test");

        assertEquals("VALIDATION_PASSED", result.getStatus());
        verify(reviewRecordRepository, never()).insert(any(io.github.legacygraph.entity.ReviewRecord.class));
    }

    @Test
    void runValidation_anyFail_setsFailedAndCreatesReview() {
        ChangeTask task = new ChangeTask();
        task.setId("chg-4");
        task.setProjectId("p1");
        task.setVersionId("v1");
        task.setTitle("修复X");
        when(changeTaskRepository.selectById("chg-4")).thenReturn(task);
        when(validationGateRunner.runAll(eq("chg-4"), any())).thenReturn(false);

        ChangeTask result = service.runValidation("chg-4", List.of("case-1"), null, "test");

        assertEquals("VALIDATION_FAILED", result.getStatus());
        verify(reviewRecordRepository).insert(any(io.github.legacygraph.entity.ReviewRecord.class));
    }

    @Test
    void createPr_delegatesToOrchestratorAndSetsPrReady() {
        ChangeTask task = new ChangeTask();
        task.setId("chg-5");
        task.setProjectId("p1");
        task.setStatus("VALIDATION_PASSED");
        when(changeTaskRepository.selectById("chg-5")).thenReturn(task);

        io.github.legacygraph.entity.PrTask prTask = new io.github.legacygraph.entity.PrTask();
        prTask.setId("pr-1");
        prTask.setBranchName("legacygraph/bugfix/chg-5-x");
        when(prOrchestrator.createPrDraft(eq(task), any())).thenReturn(prTask);

        io.github.legacygraph.entity.PrTask result = service.createPr("chg-5");

        assertEquals("pr-1", result.getId());
        assertEquals("PR_READY", task.getStatus());
        verify(changeTaskRepository).updateById(task);
    }
}
