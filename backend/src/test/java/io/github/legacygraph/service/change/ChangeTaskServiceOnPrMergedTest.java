package io.github.legacygraph.service.change;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.ChangeImpactAgent;
import io.github.legacygraph.agent.adapter.AddColumnPatchAgentAdapter;
import io.github.legacygraph.agent.adapter.MigrationAgentAdapter;
import io.github.legacygraph.agent.adapter.RefactorAgentAdapter;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.PatchFile;
import io.github.legacygraph.repository.*;
import io.github.legacygraph.service.test.PatchPlanValidator;
import io.github.legacygraph.service.test.ValidationGateRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChangeTaskServiceOnPrMergedTest {

    @Mock private ChangeTaskRepository changeTaskRepository;
    @Mock private PatchFileRepository patchFileRepository;
    @Mock private ValidationGateRepository validationGateRepository;
    @Mock private ReviewRecordRepository reviewRecordRepository;
    @Mock private ImpactSubgraphService impactSubgraphService;
    @Mock private ChangeImpactAgent changeImpactAgent;
    @Mock private RefactorAgentAdapter refactorAgentAdapter;
    @Mock private MigrationAgentAdapter migrationAgentAdapter;
    @Mock private AddColumnPatchAgentAdapter addColumnPatchAgentAdapter;
    @Mock private io.github.legacygraph.agent.PatchPlanAgent patchPlanAgent;
    @Mock private ValidationGateRunner validationGateRunner;
    @Mock private PrOrchestrator prOrchestrator;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ColumnIngestService columnIngestService;
    @Mock private Neo4jGraphDao neo4jGraphDao;

    private ChangeTaskService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ChangeTaskService(changeTaskRepository, patchFileRepository,
                validationGateRepository, reviewRecordRepository, impactSubgraphService,
                changeImpactAgent, refactorAgentAdapter, migrationAgentAdapter,
                addColumnPatchAgentAdapter, patchPlanAgent, new PatchPlanValidator(), validationGateRunner,
                prOrchestrator, objectMapper, transactionTemplate, columnIngestService, neo4jGraphDao);
    }

    @Test
    void onPrMergedSetsStatusToMerged() {
        ChangeTask task = new ChangeTask();
        task.setId("chg-1");
        task.setProjectId("p1");
        when(changeTaskRepository.selectById("chg-1")).thenReturn(task);

        LambdaQueryChainWrapper<PatchFile> chainMock = mockChain(List.of());

        ChangeTask result = service.onPrMerged("chg-1");

        assertEquals("MERGED", result.getStatus());
        verify(changeTaskRepository).updateById(task);
        // chain was invoked
        verify(patchFileRepository).lambdaQuery();
        verify(chainMock).list();
    }

    @Test
    void onPrMergedMarksPatchFilesStale() {
        ChangeTask task = new ChangeTask();
        task.setId("chg-2");
        task.setProjectId("p1");
        when(changeTaskRepository.selectById("chg-2")).thenReturn(task);

        PatchFile pf = new PatchFile();
        pf.setFilePath("src/A.java");
        mockChain(List.of(pf));

        when(neo4jGraphDao.findNodesBySourcePath("p1", null, "src/A.java"))
                .thenReturn(List.of(
                        Map.of("nodeId", "node-1"),
                        Map.of("nodeId", "node-2")));

        service.onPrMerged("chg-2");

        verify(neo4jGraphDao).setNodeProperty("node-1", "stale", true);
        verify(neo4jGraphDao).setNodeProperty("node-2", "stale", true);
    }

    @Test
    void onPrMergedWithNoPatchesStillSucceeds() {
        ChangeTask task = new ChangeTask();
        task.setId("chg-3");
        task.setProjectId("p1");
        when(changeTaskRepository.selectById("chg-3")).thenReturn(task);

        mockChain(List.of());

        ChangeTask result = service.onPrMerged("chg-3");

        assertEquals("MERGED", result.getStatus());
        verify(neo4jGraphDao, never()).findNodesBySourcePath(any(), any(), any());
        verify(neo4jGraphDao, never()).setNodeProperty(any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryChainWrapper<PatchFile> mockChain(List<PatchFile> returnList) {
        LambdaQueryChainWrapper<PatchFile> chain = mock(LambdaQueryChainWrapper.class);
        when(patchFileRepository.lambdaQuery()).thenReturn(chain);
        when(chain.eq(any(), any())).thenReturn(chain);
        when(chain.list()).thenReturn(returnList);
        return chain;
    }
}
