package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.tenant.TenantQuotaManager;
import io.github.legacygraph.tenant.TenantQuotaManager.QuotaType;
import io.github.legacygraph.tenant.TenantQuotaManager.TenantQuota;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuotaControllerTest {

    @Mock
    private TenantQuotaManager quotaManager;

    private QuotaController controller;

    @BeforeEach
    void setUp() {
        controller = new QuotaController(quotaManager);
    }

    @Test
    void getQuota_returnsAllQuotaTypes() {
        TenantQuota quota = TenantQuota.defaultQuota();
        when(quotaManager.getQuota("p1")).thenReturn(quota);

        Result<Map<String, Object>> result = controller.getQuota("p1");

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());

        // 验证包含所有 QuotaType
        for (QuotaType type : QuotaType.values()) {
            assertTrue(result.getData().containsKey(type.name()),
                    "应包含配额类型: " + type.name());
            Map<String, Long> item = (Map<String, Long>) result.getData().get(type.name());
            assertNotNull(item.get("limit"));
            assertNotNull(item.get("usage"));
            assertEquals(type.getDefaultLimit(), item.get("limit"));
            assertEquals(0L, item.get("usage"));
        }

        verify(quotaManager).getQuota("p1");
    }

    @Test
    void getQuota_withUsage_returnsCorrectValues() {
        TenantQuota quota = TenantQuota.defaultQuota();
        quota.addUsage(QuotaType.NODES, 500);
        quota.addUsage(QuotaType.EDGES, 2000);
        when(quotaManager.getQuota("p1")).thenReturn(quota);

        Result<Map<String, Object>> result = controller.getQuota("p1");

        Map<String, Long> nodesItem = (Map<String, Long>) result.getData().get("NODES");
        assertEquals(500L, nodesItem.get("usage"));

        Map<String, Long> edgesItem = (Map<String, Long>) result.getData().get("EDGES");
        assertEquals(2000L, edgesItem.get("usage"));
    }

    @Test
    void getQuota_resultContainsAllFiveQuotaTypes() {
        TenantQuota quota = TenantQuota.defaultQuota();
        when(quotaManager.getQuota("p1")).thenReturn(quota);

        Result<Map<String, Object>> result = controller.getQuota("p1");

        // 应有 5 种配额类型
        assertEquals(QuotaType.values().length, result.getData().size());
    }
}
