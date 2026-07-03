package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.tenant.TenantQuotaManager;
import io.github.legacygraph.tenant.TenantQuotaManager.QuotaType;
import io.github.legacygraph.tenant.TenantQuotaManager.TenantQuota;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 租户配额控制器（P3-5）
 */
@RestController
@RequestMapping("/lg/quota")
@Tag(name = "资源配额", description = "多租户资源配额查询与管理")
public class QuotaController {

    private final TenantQuotaManager quotaManager;

    public QuotaController(TenantQuotaManager quotaManager) {
        this.quotaManager = quotaManager;
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "查询项目配额", description = "返回各类型资源的配额上限和当前用量")
    public Result<Map<String, Object>> getQuota(@PathVariable String projectId) {
        TenantQuota quota = quotaManager.getQuota(projectId);
        Map<String, Object> result = new HashMap<>();
        for (QuotaType type : QuotaType.values()) {
            Map<String, Long> item = new HashMap<>();
            item.put("limit", quota.getLimit(type));
            item.put("usage", quota.getUsage(type));
            result.put(type.name(), item);
        }
        return Result.success(result);
    }
}
