package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.PrDescriptionAgent;
import io.github.legacygraph.dto.PrDescription;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.PatchFile;
import io.github.legacygraph.entity.PrTask;
import io.github.legacygraph.entity.ValidationGate;
import io.github.legacygraph.repository.PatchFileRepository;
import io.github.legacygraph.repository.PrTaskRepository;
import io.github.legacygraph.repository.ValidationGateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.change.PrOrchestrator;

@ExtendWith(MockitoExtension.class)
class PrOrchestratorTest {

    @Mock private PrTaskRepository prTaskRepository;
    @Mock private PatchFileRepository patchFileRepository;
    @Mock private ValidationGateRepository validationGateRepository;
    @Mock private PrDescriptionAgent prDescriptionAgent;
    @Mock private io.github.legacygraph.repository.CodeRepoRepository codeRepoRepository;
    @Mock private io.github.legacygraph.repository.ChangeTaskRepository changeTaskRepository;
    @Mock private org.springframework.beans.factory.ObjectProvider<List<io.github.legacygraph.service.pr.GitProviderAdapter>> gitProviderAdaptersProvider;

    private PrOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        orchestrator = new PrOrchestrator(prTaskRepository, patchFileRepository,
                validationGateRepository, prDescriptionAgent, objectMapper,
                codeRepoRepository, changeTaskRepository, gitProviderAdaptersProvider);
    }

    private ChangeTask task(String type, String status) {
        ChangeTask t = new ChangeTask();
        t.setId("chg-12345678-abc");
        t.setProjectId("p1");
        t.setTaskType(type);
        t.setStatus(status);
        t.setTitle("修复X");
        return t;
    }

    private void stubGates(ValidationGate... gates) {
        when(validationGateRepository.lambdaQuery()).thenReturn(
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class, RETURNS_DEEP_STUBS));
        when(validationGateRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(List.of(gates));
    }

    private void stubNoPatches() {
        when(patchFileRepository.lambdaQuery()).thenReturn(
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class, RETURNS_DEEP_STUBS));
        when(patchFileRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(List.of());
    }

    private ValidationGate gate(String result) {
        ValidationGate g = new ValidationGate();
        g.setResult(result);
        return g;
    }

    @Test
    void notValidated_rejectsPrCreation() {
        ChangeTask t = task("BUGFIX", "PATCH_DRAFTED");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orchestrator.createPrDraft(t, LocalDateTime.now()));
        assertTrue(ex.getMessage().contains("未通过验证门禁"));
        verify(prTaskRepository, never()).insert(any(PrTask.class));
    }

    @Test
    void failedGate_rejectsPrCreation() {
        ChangeTask t = task("BUGFIX", "VALIDATION_PASSED");
        stubGates(gate("PASSED"), gate("FAILED"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orchestrator.createPrDraft(t, LocalDateTime.now()));
        assertTrue(ex.getMessage().contains("未通过的验证门禁"));
    }

    @Test
    void bugfix_createsDraft_withFeatureBranchAndOneReviewer() {
        ChangeTask t = task("BUGFIX", "VALIDATION_PASSED");
        stubGates(gate("PASSED"));
        stubNoPatches();
        when(prDescriptionAgent.generate(any(), any(), any(), any()))
                .thenReturn(new PrDescription());

        PrTask pr = orchestrator.createPrDraft(t, LocalDateTime.now());

        assertEquals("DRAFT", pr.getPrStatus());
        assertTrue(pr.getBranchName().startsWith("legacygraph/bugfix/"));
        assertNotNull(pr.getRollbackPlan());
        assertTrue(pr.getReviewerPolicy().contains("\"minReviewers\":1"));
        verify(prTaskRepository).insert(any(PrTask.class));
    }

    @Test
    void upgrade_requiresTwoReviewersAndDba() {
        ChangeTask t = task("UPGRADE", "VALIDATION_PASSED");
        stubGates(gate("PASSED"));
        stubNoPatches();
        when(prDescriptionAgent.generate(any(), any(), any(), any())).thenReturn(new PrDescription());

        PrTask pr = orchestrator.createPrDraft(t, LocalDateTime.now());

        assertTrue(pr.getReviewerPolicy().contains("\"minReviewers\":2"));
        assertTrue(pr.getReviewerPolicy().contains("\"dbaRequired\":true"));
    }

    @Test
    void schemaChange_forcesDbaReviewer() {
        ChangeTask t = task("BUGFIX", "VALIDATION_PASSED");
        stubGates(gate("PASSED"));
        // 补丁触碰迁移脚本 → schema 变更
        PatchFile pf = new PatchFile();
        pf.setFilePath("backend/src/main/resources/db/migration/V10__x.sql");
        pf.setChangeType("CREATE");
        when(patchFileRepository.lambdaQuery()).thenReturn(
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class, RETURNS_DEEP_STUBS));
        when(patchFileRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(List.of(pf));
        when(prDescriptionAgent.generate(any(), any(), any(), any())).thenReturn(new PrDescription());

        PrTask pr = orchestrator.createPrDraft(t, LocalDateTime.now());

        assertTrue(pr.getReviewerPolicy().contains("\"dbaRequired\":true"));
        assertTrue(pr.getRollbackPlan().contains("\"dbBackupRequired\":true"));
    }

    @Test
    void agentFailure_doesNotBlockDraft() {
        ChangeTask t = task("BUGFIX", "VALIDATION_PASSED");
        stubGates(gate("PASSED"));
        stubNoPatches();
        when(prDescriptionAgent.generate(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("LLM down"));

        PrTask pr = orchestrator.createPrDraft(t, LocalDateTime.now());
        assertEquals("DRAFT", pr.getPrStatus());
        verify(prTaskRepository).insert(any(PrTask.class));
    }
}
