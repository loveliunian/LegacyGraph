package io.github.legacygraph.dto.requirement;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 契约生成请求（G-17）。
 * <p>指定从需求生成 API 契约的格式与基础路径。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContractGenerationRequest {

    /** 契约格式：OPENAPI 或 TYPESCRIPT */
    private String format;

    /** 端点基础路径，如 /api/v1（可选，覆盖默认 basePath） */
    private String endpointBase;
}
