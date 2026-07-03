package io.github.legacygraph.service;

import io.github.legacygraph.builder.FeatureSliceBuilder;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.FeatureSlice;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.change.ChangeReportService;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;

@ExtendWith(MockitoExtension.class)
class ChangeReportServiceTest {

    @Mock private FeatureSliceBuilder featureSliceBuilder;
    @Mock private Neo4jGraphDao neo4jGraphDao;
    @Mock private NodeEvidenceRepository nodeEvidenceRepository;
    @Mock private EvidenceRepository evidenceRepository;
    @Mock private TestCaseRepository testCaseRepository;
    @Mock private TestResultRepository testResultRepository;
    @Mock private ChangeTaskRepository changeTaskRepository;
    @Mock private PatchFileRepository patchFileRepository;
    @Mock private ValidationGateRepository validationGateRepository;
    @Mock private PrTaskRepository prTaskRepository;

    private ChangeReportService service;

    @BeforeEach
    void setUp() {
        service = new ChangeReportService(featureSliceBuilder, neo4jGraphDao,
                nodeEvidenceRepository, evidenceRepository, testCaseRepository,
                testResultRepository, changeTaskRepository, patchFileRepository,
                validationGateRepository, prTaskRepository, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void featureSliceMarkdown_containsOverviewAndNodes() {
        FeatureSlice slice = FeatureSlice.builder()
                .sliceId("feature:ticket_dispatch")
                .projectId("p1")
                .name("工单派发")
                .featureName("工单派发")
                .entryPage("TicketDetail.vue")
                .status("ACTIVE")
                .confidence(new BigDecimal("0.93"))
                .coverageStatus("COVERED")
                .riskLevel("LOW")
                .apiIds(List.of("api-1"))
                .build();
        when(featureSliceBuilder.buildSliceById("p1", "feature:ticket_dispatch")).thenReturn(slice);

        GraphNode api = new GraphNode();
        api.setId("api-1");
        api.setNodeName("POST /ticket/dispatch");
        api.setDisplayName("POST /ticket/dispatch");
        when(neo4jGraphDao.findNodeById("api-1")).thenReturn(Optional.of(api));
        when(nodeEvidenceRepository.lambdaQuery()).thenReturn(
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class, RETURNS_DEEP_STUBS));

        String md = service.generateFeatureSliceMarkdown("p1", "feature:ticket_dispatch");

        assertTrue(md.contains("# 功能说明书：工单派发"));
        assertTrue(md.contains("| 功能键 | feature:ticket_dispatch |"));
        assertTrue(md.contains("POST /ticket/dispatch"));
        assertTrue(md.contains("*由 LegacyGraph 自动生成*"));
    }

    @Test
    void changeTaskMarkdown_missingTask_returnsNotFound() {
        when(changeTaskRepository.selectById("missing")).thenReturn(null);
        String md = service.generateChangeTaskMarkdown("p1", "missing");
        assertTrue(md.contains("未找到"));
        assertTrue(md.contains("missing"));
    }

    @Test
    void changeTaskMarkdown_rendersTaskOverview() {
        ChangeTask task = new ChangeTask();
        task.setId("chg-1");
        task.setTitle("工单派发状态错误");
        task.setTaskType("BUGFIX");
        task.setStatus("OPEN");
        task.setRiskLevel("MEDIUM");
        when(changeTaskRepository.selectById("chg-1")).thenReturn(task);
        when(patchFileRepository.lambdaQuery()).thenReturn(
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class, RETURNS_DEEP_STUBS));
        when(validationGateRepository.lambdaQuery()).thenReturn(
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class, RETURNS_DEEP_STUBS));
        when(prTaskRepository.lambdaQuery()).thenReturn(
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class, RETURNS_DEEP_STUBS));

        String md = service.generateChangeTaskMarkdown("p1", "chg-1");

        assertTrue(md.contains("# 变更任务：工单派发状态错误"));
        assertTrue(md.contains("| 类型 | BUGFIX |"));
        assertTrue(md.contains("| 状态 | OPEN |"));
    }
}
