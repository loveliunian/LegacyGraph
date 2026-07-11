package io.github.legacygraph.service.scan;

import io.github.legacygraph.common.GraphReleaseStatus;
import io.github.legacygraph.dto.qa.QaEvaluationResult;
import io.github.legacygraph.dto.scan.Decision;
import io.github.legacygraph.entity.GraphRelease;
import io.github.legacygraph.service.graph.GraphReleaseService;
import io.github.legacygraph.service.qa.QaEvaluationService;
import io.github.legacygraph.service.qa.SemanticCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScanFinalizationService 单元测试 — 验证收口编排流程。
 *
 * <p>测试场景：
 * <ul>
 *   <li>门禁通过：9 步全执行，GraphRelease 走 startValidation → markPublished，缓存失效</li>
 *   <li>门禁不通过：走 startValidation → markFailed + 记录原因，缓存不失效</li>
 *   <li>某步骤异常不阻塞后续步骤</li>
 *   <li>门禁评估异常：标记 markFailed</li>
 *   <li>startValidation 返回已 PUBLISHED 的幂等记录：不重复 markPublished，但失效缓存</li>
 *   <li>startValidation 返回已 FAILED 的幂等记录：不重复 markFailed</li>
 *   <li>空社区检测结果：跳过 writeCommunityToNodes</li>
 *   <li>startValidation 异常：不调用 markPublished / markFailed</li>
 *   <li>markPublished 异常：不抛出，缓存不失效</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScanFinalizationService 收口编排测试")
class ScanFinalizationServiceTest {

    @Mock
    private ProjectConventionIngestService projectConventionIngestService;
    @Mock
    private ReusableComponentMarker reusableComponentMarker;
    @Mock
    private GraphQualityAssessor graphQualityAssessor;
    @Mock
    private EdgeCompletionService edgeCompletionService;
    @Mock
    private CommunityDetectionService communityDetectionService;
    @Mock
    private ScanArtifactPublisher scanArtifactPublisher;
    @Mock
    private GraphQualityGate graphQualityGate;
    @Mock
    private GraphReleaseService graphReleaseService;
    @Mock
    private SemanticCache semanticCache;
    @Mock
    private QaEvaluationService qaEvaluationService;

    private ScanFinalizationService service;

    private static final String PID = "proj-test";
    private static final String VID = "v-test";

    @BeforeEach
    void setUp() {
        service = new ScanFinalizationService(
                projectConventionIngestService,
                reusableComponentMarker,
                graphQualityAssessor,
                edgeCompletionService,
                communityDetectionService,
                scanArtifactPublisher,
                graphQualityGate,
                graphReleaseService,
                semanticCache,
                qaEvaluationService);
    }

    /**
     * 设置 lenient 默认 stub：edgeCompletion 返回空报告，communityDetection 返回空 map，
     * QA 评测门禁默认通过（仅当质量门禁通过时才会被调用）。
     * 各测试可在此基础上覆盖特定 stub。
     */
    private void stubDefaults() {
        lenient().when(edgeCompletionService.completeAll(eq(PID), eq(VID)))
                .thenReturn(new EdgeCompletionService.CompletionReport(PID, VID));
        lenient().when(communityDetectionService.detectCommunities(eq(PID)))
                .thenReturn(Map.of());
        // QA 评测门禁默认通过（仅当质量门禁通过时才会被调用）
        QaEvaluationResult qaPass = new QaEvaluationResult();
        qaPass.setPassed(true);
        qaPass.setEntityRecall(0.95);
        qaPass.setEvidencePrecision(0.95);
        qaPass.setAbstentionAccuracy(1.0);
        lenient().when(qaEvaluationService.runSmoke(eq(PID), eq(VID)))
                .thenReturn(qaPass);
    }

    // ========================================================
    // 场景 1：门禁通过 → startValidation → markPublished → 缓存失效
    // ========================================================

    @Test
    @DisplayName("门禁通过时应走 startValidation → markPublished 并失效缓存")
    void shouldPublishAndInvalidateCacheWhenGatePassed() {
        stubDefaults();
        Decision passed = new Decision(true, List.of());
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(passed);

        GraphRelease release = buildRelease("release-1", GraphReleaseStatus.VALIDATING);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(release);

        service.finalize(PID, VID);

        // 验证 9 步全部执行
        verify(projectConventionIngestService).ingest(eq(PID), eq(VID));
        verify(reusableComponentMarker).mark(eq(PID), eq(VID));
        verify(graphQualityAssessor).assessAndReport(eq(PID), eq(VID));
        verify(edgeCompletionService).completeAll(eq(PID), eq(VID));
        verify(scanArtifactPublisher).publishArtifactsOnly(eq(PID), eq(VID));
        verify(graphQualityGate).evaluate(eq(PID), eq(VID));

        // 验证发布 + 缓存失效
        verify(graphReleaseService).startValidation(eq(PID), eq(VID));
        verify(graphReleaseService).markPublished(eq("release-1"));
        verify(semanticCache).invalidateByProject(eq(PID));
    }

    // ========================================================
    // 场景 2：门禁不通过 → startValidation → markFailed + 记录原因，缓存不失效
    // ========================================================

    @Test
    @DisplayName("门禁不通过时应走 markFailed 并记录原因，不失效缓存")
    void shouldMarkFailedWhenGateNotPassed() {
        stubDefaults();
        List<String> reasons = List.of("EDGE_NODE_RATIO_BELOW_1", "CONSTRAINT_VIOLATIONS");
        Decision failed = new Decision(false, reasons);
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(failed);

        GraphRelease release = buildRelease("release-2", GraphReleaseStatus.VALIDATING);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(release);

        service.finalize(PID, VID);

        // 验证 markFailed 调用，reasons 透传
        verify(graphReleaseService).markFailed(eq("release-2"), eq(reasons));

        // 不调用 markPublished，不失效缓存
        verify(graphReleaseService, never()).markPublished(anyString());
        verify(semanticCache, never()).invalidateByProject(anyString());
    }

    // ========================================================
    // 场景 3：某步骤异常不阻塞后续步骤
    // ========================================================

    @Test
    @DisplayName("约定提取异常不应阻塞后续步骤")
    void shouldContinueWhenConventionIngestThrows() {
        stubDefaults();
        org.mockito.Mockito.doThrow(new RuntimeException("convention ingest error"))
                .when(projectConventionIngestService).ingest(eq(PID), eq(VID));
        Decision passed = new Decision(true, List.of());
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(passed);
        GraphRelease release = buildRelease("release-3", GraphReleaseStatus.VALIDATING);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(release);

        service.finalize(PID, VID);

        // 后续步骤仍执行
        verify(reusableComponentMarker).mark(eq(PID), eq(VID));
        verify(graphQualityAssessor).assessAndReport(eq(PID), eq(VID));
        verify(scanArtifactPublisher).publishArtifactsOnly(eq(PID), eq(VID));
        verify(graphReleaseService).markPublished(eq("release-3"));
    }

    @Test
    @DisplayName("边补全异常不应阻塞后续步骤")
    void shouldContinueWhenEdgeCompletionThrows() {
        stubDefaults();
        when(edgeCompletionService.completeAll(eq(PID), eq(VID)))
                .thenThrow(new RuntimeException("edge completion error"));
        Decision passed = new Decision(true, List.of());
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(passed);
        GraphRelease release = buildRelease("release-4", GraphReleaseStatus.VALIDATING);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(release);

        service.finalize(PID, VID);

        verify(scanArtifactPublisher).publishArtifactsOnly(eq(PID), eq(VID));
        verify(graphReleaseService).markPublished(eq("release-4"));
    }

    @Test
    @DisplayName("产物发布异常不应阻塞质量门禁评估与 GraphRelease 发布")
    void shouldContinueWhenArtifactPublishThrows() {
        stubDefaults();
        org.mockito.Mockito.doThrow(new RuntimeException("publish error"))
                .when(scanArtifactPublisher).publishArtifactsOnly(eq(PID), eq(VID));
        Decision passed = new Decision(true, List.of());
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(passed);
        GraphRelease release = buildRelease("release-5", GraphReleaseStatus.VALIDATING);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(release);

        service.finalize(PID, VID);

        verify(graphQualityGate).evaluate(eq(PID), eq(VID));
        verify(graphReleaseService).markPublished(eq("release-5"));
    }

    // ========================================================
    // 场景 4：门禁评估异常 → 标记 markFailed
    // ========================================================

    @Test
    @DisplayName("门禁评估异常时应标记 markFailed 并记录错误原因")
    void shouldMarkFailedWhenGateEvaluationThrows() {
        stubDefaults();
        when(graphQualityGate.evaluate(eq(PID), eq(VID)))
                .thenThrow(new RuntimeException("gate evaluation error"));
        GraphRelease release = buildRelease("release-6", GraphReleaseStatus.VALIDATING);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(release);

        service.finalize(PID, VID);

        // 验证 markFailed 调用，reasons 包含异常信息
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(graphReleaseService).markFailed(eq("release-6"), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0)).contains("QUALITY_GATE_EVALUATION_ERROR");

        // 不调用 markPublished，不失效缓存
        verify(graphReleaseService, never()).markPublished(anyString());
        verify(semanticCache, never()).invalidateByProject(anyString());
    }

    // ========================================================
    // 场景 5：startValidation 返回已 PUBLISHED 的幂等记录
    // ========================================================

    @Test
    @DisplayName("startValidation 返回已 PUBLISHED 记录时不重复 markPublished 但失效缓存")
    void shouldSkipMarkPublishedButInvalidateCacheWhenAlreadyPublished() {
        stubDefaults();
        Decision passed = new Decision(true, List.of());
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(passed);

        GraphRelease alreadyPublished = buildRelease("release-7", GraphReleaseStatus.PUBLISHED);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(alreadyPublished);

        service.finalize(PID, VID);

        verify(graphReleaseService, never()).markPublished(anyString());
        // 已发布幂等场景也失效缓存
        verify(semanticCache).invalidateByProject(eq(PID));
    }

    // ========================================================
    // 场景 6：startValidation 返回已 FAILED 的幂等记录
    // ========================================================

    @Test
    @DisplayName("startValidation 返回已 FAILED 记录时不重复 markFailed")
    void shouldSkipMarkFailedWhenAlreadyFailed() {
        stubDefaults();
        Decision failed = new Decision(false, List.of("CONSTRAINT_VIOLATIONS"));
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(failed);

        GraphRelease alreadyFailed = buildRelease("release-8", GraphReleaseStatus.FAILED);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(alreadyFailed);

        service.finalize(PID, VID);

        verify(graphReleaseService, never()).markFailed(anyString(), any());
        verify(semanticCache, never()).invalidateByProject(anyString());
    }

    // ========================================================
    // 场景 7：空社区检测结果 → 跳过 writeCommunityToNodes
    // ========================================================

    @Test
    @DisplayName("社区检测结果为空时应跳过 writeCommunityToNodes")
    void shouldSkipWriteCommunityWhenResultEmpty() {
        stubDefaults();
        // detectCommunities 默认返回空 map（stubDefaults）
        Decision passed = new Decision(true, List.of());
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(passed);
        GraphRelease release = buildRelease("release-9", GraphReleaseStatus.VALIDATING);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(release);

        service.finalize(PID, VID);

        verify(communityDetectionService).detectCommunities(eq(PID));
        verify(communityDetectionService, never())
                .writeCommunityToNodes(anyString(), any());
    }

    @Test
    @DisplayName("社区检测结果非空时应调用 writeCommunityToNodes")
    void shouldCallWriteCommunityWhenResultNotEmpty() {
        stubDefaults();
        Map<String, String> communities = Map.of("com.example.service", "community-1");
        when(communityDetectionService.detectCommunities(eq(PID))).thenReturn(communities);
        Decision passed = new Decision(true, List.of());
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(passed);
        GraphRelease release = buildRelease("release-10", GraphReleaseStatus.VALIDATING);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(release);

        service.finalize(PID, VID);

        verify(communityDetectionService).writeCommunityToNodes(eq(PID), eq(communities));
    }

    // ========================================================
    // 场景 8：startValidation 异常 → 不调用 markPublished / markFailed
    // ========================================================

    @Test
    @DisplayName("startValidation 异常时门禁通过路径不调用 markPublished")
    void shouldNotCallMarkPublishedWhenStartValidationThrows() {
        stubDefaults();
        Decision passed = new Decision(true, List.of());
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(passed);
        when(graphReleaseService.startValidation(eq(PID), eq(VID)))
                .thenThrow(new RuntimeException("startValidation error"));

        service.finalize(PID, VID);

        verify(graphReleaseService, never()).markPublished(anyString());
        verify(semanticCache, never()).invalidateByProject(anyString());
    }

    @Test
    @DisplayName("startValidation 异常时门禁不通过路径不调用 markFailed")
    void shouldNotCallMarkFailedWhenStartValidationThrows() {
        stubDefaults();
        Decision failed = new Decision(false, List.of("CONSTRAINT_VIOLATIONS"));
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(failed);
        when(graphReleaseService.startValidation(eq(PID), eq(VID)))
                .thenThrow(new RuntimeException("startValidation error"));

        service.finalize(PID, VID);

        verify(graphReleaseService, never()).markFailed(anyString(), any());
    }

    // ========================================================
    // 场景 9：markPublished 异常 → 不抛出，缓存不失效
    // ========================================================

    @Test
    @DisplayName("markPublished 异常时不应抛出且不失效缓存")
    void shouldNotInvalidateCacheWhenMarkPublishedThrows() {
        stubDefaults();
        Decision passed = new Decision(true, List.of());
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(passed);
        GraphRelease release = buildRelease("release-11", GraphReleaseStatus.VALIDATING);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(release);
        when(graphReleaseService.markPublished(eq("release-11")))
                .thenThrow(new RuntimeException("markPublished error"));

        service.finalize(PID, VID);

        // markPublished 异常，缓存不应失效
        verify(semanticCache, never()).invalidateByProject(anyString());
    }

    // ========================================================
    // 场景 10：验证步骤执行顺序（通过 InOrder 验证关键调用顺序）
    // ========================================================

    @Test
    @DisplayName("约定提取应在质量评估之前执行")
    void shouldExecuteStepsInOrder() {
        stubDefaults();
        Decision passed = new Decision(true, List.of());
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(passed);
        GraphRelease release = buildRelease("release-12", GraphReleaseStatus.VALIDATING);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(release);

        service.finalize(PID, VID);

        // 通过 Mockito 的 inOrder 验证关键顺序
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
                projectConventionIngestService,
                graphQualityAssessor,
                edgeCompletionService,
                scanArtifactPublisher,
                graphQualityGate,
                graphReleaseService,
                semanticCache);

        inOrder.verify(projectConventionIngestService).ingest(eq(PID), eq(VID));
        inOrder.verify(graphQualityAssessor).assessAndReport(eq(PID), eq(VID));
        inOrder.verify(edgeCompletionService).completeAll(eq(PID), eq(VID));
        inOrder.verify(scanArtifactPublisher).publishArtifactsOnly(eq(PID), eq(VID));
        inOrder.verify(graphQualityGate).evaluate(eq(PID), eq(VID));
        inOrder.verify(graphReleaseService).startValidation(eq(PID), eq(VID));
        inOrder.verify(graphReleaseService).markPublished(eq("release-12"));
        inOrder.verify(semanticCache).invalidateByProject(eq(PID));
    }

    // ========================================================
    // 场景 11：markFailed 接收 null reasons 时不报错
    // ========================================================

    @Test
    @DisplayName("门禁不通过且 reasons 为 null 时 markFailed 应接收空列表")
    void shouldHandleNullReasonsWhenMarkFailed() {
        stubDefaults();
        // Decision 的 reasons 在 passed=false 时通常非空，但防御 null
        Decision failed = new Decision(false, null);
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(failed);
        GraphRelease release = buildRelease("release-13", GraphReleaseStatus.VALIDATING);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(release);

        service.finalize(PID, VID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(graphReleaseService).markFailed(eq("release-13"), captor.capture());
        // reasons 为 null 时应转为空列表，不报错
        assertThat(captor.getValue()).isNotNull();
    }

    // ========================================================
    // 场景 12：全部门禁通过但所有步骤验证
    // ========================================================

    @Test
    @DisplayName("完整流程应调用所有依赖服务各一次")
    void shouldInvokeAllDependenciesOnceInFullFlow() {
        stubDefaults();
        when(communityDetectionService.detectCommunities(eq(PID)))
                .thenReturn(Map.of("pkg.a", "comm-1"));
        Decision passed = new Decision(true, List.of());
        when(graphQualityGate.evaluate(eq(PID), eq(VID))).thenReturn(passed);
        GraphRelease release = buildRelease("release-14", GraphReleaseStatus.VALIDATING);
        when(graphReleaseService.startValidation(eq(PID), eq(VID))).thenReturn(release);

        service.finalize(PID, VID);

        verify(projectConventionIngestService, times(1)).ingest(eq(PID), eq(VID));
        verify(reusableComponentMarker, times(1)).mark(eq(PID), eq(VID));
        verify(graphQualityAssessor, times(1)).assessAndReport(eq(PID), eq(VID));
        verify(edgeCompletionService, times(1)).completeAll(eq(PID), eq(VID));
        verify(communityDetectionService, times(1)).detectCommunities(eq(PID));
        verify(communityDetectionService, times(1)).writeCommunityToNodes(eq(PID), any());
        verify(scanArtifactPublisher, times(1)).publishArtifactsOnly(eq(PID), eq(VID));
        verify(graphQualityGate, times(1)).evaluate(eq(PID), eq(VID));
        verify(graphReleaseService, times(1)).startValidation(eq(PID), eq(VID));
        verify(graphReleaseService, times(1)).markPublished(eq("release-14"));
        verify(semanticCache, times(1)).invalidateByProject(eq(PID));
    }

    // ========================================================
    // 辅助方法
    // ========================================================

    private GraphRelease buildRelease(String id, GraphReleaseStatus status) {
        GraphRelease release = new GraphRelease();
        release.setId(id);
        release.setProjectId(PID);
        release.setScanVersionId(VID);
        release.setStatus(status.name());
        release.setCreatedAt(LocalDateTime.now());
        return release;
    }
}
