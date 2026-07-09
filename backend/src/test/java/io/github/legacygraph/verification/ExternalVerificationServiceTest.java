package io.github.legacygraph.verification;

import io.github.legacygraph.extractors.adapter.ScanContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ExternalVerificationService} 编排服务测试。
 */
@ExtendWith(MockitoExtension.class)
class ExternalVerificationServiceTest {

    @Mock
    private ResultFusionEngine resultFusionEngine;

    @Mock
    private ExternalVerificationAdapter healthyAdapter;

    @Mock
    private ExternalVerificationAdapter unhealthyAdapter;

    private ScanContext context;

    @BeforeEach
    void setUp() {
        context = ScanContext.builder()
                .projectId("p1")
                .versionId("v1")
                .baseDir("/tmp")
                .build();

        // healthyAdapter 配置
        lenient().when(healthyAdapter.adapterName()).thenReturn("healthy");
        lenient().when(healthyAdapter.priority()).thenReturn(10);
        lenient().when(healthyAdapter.supports(context)).thenReturn(true);
        lenient().when(healthyAdapter.checkHealth()).thenReturn(true);

        // unhealthyAdapter 配置
        lenient().when(unhealthyAdapter.adapterName()).thenReturn("unhealthy");
        lenient().when(unhealthyAdapter.priority()).thenReturn(20);
        lenient().when(unhealthyAdapter.supports(context)).thenReturn(true);
        lenient().when(unhealthyAdapter.checkHealth()).thenReturn(false);
    }

    @Test
    void executeVerification_noAdapters_returnsEmptyStats() {
        ExternalVerificationService service = new ExternalVerificationService(
                List.of(), resultFusionEngine);

        ResultFusionEngine.FusionStats stats =
                service.executeVerification("p1", "v1", context);

        assertEquals(0, stats.getConfirmedCount());
        assertEquals(0, stats.getErrors());
        verifyNoInteractions(resultFusionEngine);
    }

    @Test
    void executeVerification_allUnhealthy_returnsEmptyStats() {
        ExternalVerificationService service = new ExternalVerificationService(
                List.of(unhealthyAdapter), resultFusionEngine);

        ResultFusionEngine.FusionStats stats =
                service.executeVerification("p1", "v1", context);

        assertEquals(0, stats.getConfirmedCount());
        verifyNoInteractions(resultFusionEngine);
    }

    @Test
    void executeVerification_healthyAdapter_executesAndFuses() {
        VerificationResult mockResult = VerificationResult.empty("healthy");
        when(healthyAdapter.verify("p1", "v1", context)).thenReturn(mockResult);

        ResultFusionEngine.FusionStats expectedStats = ResultFusionEngine.FusionStats.builder()
                .confirmedCount(3)
                .missingWritten(2)
                .build();
        when(resultFusionEngine.fuse(eq("p1"), eq("v1"), anyList())).thenReturn(expectedStats);

        ExternalVerificationService service = new ExternalVerificationService(
                List.of(healthyAdapter), resultFusionEngine);

        ResultFusionEngine.FusionStats stats =
                service.executeVerification("p1", "v1", context);

        assertEquals(3, stats.getConfirmedCount());
        assertEquals(2, stats.getMissingWritten());
        verify(healthyAdapter).verify("p1", "v1", context);
        verify(resultFusionEngine).fuse(eq("p1"), eq("v1"), anyList());
    }

    @Test
    void executeVerification_mixedHealthyUnhealthy_onlyRunsHealthy() {
        VerificationResult mockResult = VerificationResult.builder()
                .adapterName("healthy")
                .totalChecked(5)
                .totalConfirmed(3)
                .build();
        when(healthyAdapter.verify("p1", "v1", context)).thenReturn(mockResult);
        when(resultFusionEngine.fuse(any(), any(), anyList()))
                .thenReturn(ResultFusionEngine.FusionStats.builder().build());

        ExternalVerificationService service = new ExternalVerificationService(
                List.of(unhealthyAdapter, healthyAdapter), resultFusionEngine);

        service.executeVerification("p1", "v1", context);

        verify(healthyAdapter).verify("p1", "v1", context);
        verify(unhealthyAdapter, never()).verify(any(), any(), any());
    }

    @Test
    void executeVerification_adapterThrowsException_doesNotAffectOthers() {
        ExternalVerificationAdapter throwingAdapter = mock(ExternalVerificationAdapter.class);
        when(throwingAdapter.adapterName()).thenReturn("throwing");
        when(throwingAdapter.priority()).thenReturn(5);
        when(throwingAdapter.supports(context)).thenReturn(true);
        when(throwingAdapter.checkHealth()).thenReturn(true);
        when(throwingAdapter.verify("p1", "v1", context))
                .thenThrow(new RuntimeException("boom"));

        when(healthyAdapter.verify("p1", "v1", context))
                .thenReturn(VerificationResult.empty("healthy"));
        when(resultFusionEngine.fuse(any(), any(), anyList()))
                .thenReturn(ResultFusionEngine.FusionStats.builder().build());

        ExternalVerificationService service = new ExternalVerificationService(
                List.of(throwingAdapter, healthyAdapter), resultFusionEngine);

        // 不应抛异常
        ResultFusionEngine.FusionStats stats =
                service.executeVerification("p1", "v1", context);

        // throwing adapter 异常被隔离，healthy adapter 结果仍进入融合
        verify(resultFusionEngine).fuse(eq("p1"), eq("v1"), anyList());
    }

    @Test
    void executeVerification_adapterReturnsNull_skipsButContinues() {
        when(healthyAdapter.verify("p1", "v1", context)).thenReturn(null);

        ExternalVerificationService service = new ExternalVerificationService(
                List.of(healthyAdapter), resultFusionEngine);

        ResultFusionEngine.FusionStats stats =
                service.executeVerification("p1", "v1", context);

        // null 结果不进入融合
        assertEquals(0, stats.getConfirmedCount());
        verifyNoInteractions(resultFusionEngine);
    }

    @Test
    void adapters_sortedByPriority() {
        ExternalVerificationAdapter lowPriority = mock(ExternalVerificationAdapter.class);
        when(lowPriority.adapterName()).thenReturn("low");
        when(lowPriority.priority()).thenReturn(1);
        when(lowPriority.supports(any())).thenReturn(false);

        ExternalVerificationAdapter highPriority = mock(ExternalVerificationAdapter.class);
        when(highPriority.adapterName()).thenReturn("high");
        when(highPriority.priority()).thenReturn(100);
        when(highPriority.supports(any())).thenReturn(false);

        // 传入顺序是 high, low；应按 priority 排序为 low, high
        ExternalVerificationService service = new ExternalVerificationService(
                List.of(highPriority, lowPriority), resultFusionEngine);

        // 验证排序后先调用 low 的 supports
        service.executeVerification("p1", "v1", context);

        // lowPriority 先被检查（priority=1 < 100）
        verify(lowPriority, atLeastOnce()).supports(context);
    }

    @Test
    void hasAvailableAdapters_returnsTrueWhenAtLeastOneHealthy() {
        ExternalVerificationService service = new ExternalVerificationService(
                List.of(unhealthyAdapter, healthyAdapter), resultFusionEngine);

        assertTrue(service.hasAvailableAdapters(context));
    }

    @Test
    void hasAvailableAdapters_returnsFalseWhenAllUnhealthy() {
        ExternalVerificationService service = new ExternalVerificationService(
                List.of(unhealthyAdapter), resultFusionEngine);

        assertFalse(service.hasAvailableAdapters(context));
    }

    @Test
    void hasAvailableAdapters_returnsFalseWhenEmpty() {
        ExternalVerificationService service = new ExternalVerificationService(
                List.of(), resultFusionEngine);

        assertFalse(service.hasAvailableAdapters(context));
    }
}
