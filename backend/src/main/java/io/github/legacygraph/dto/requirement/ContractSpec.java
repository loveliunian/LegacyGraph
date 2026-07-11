package io.github.legacygraph.dto.requirement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 契约规范（G-17）。
 * <p>从需求条目自动生成的 API 契约中间表示，可转为 OpenAPI 3.0 YAML 或 TypeScript 接口定义。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractSpec {

    /** 项目 ID */
    private String projectId;

    /** 需求 ID */
    private String requirementId;

    /** 契约标题（取自需求目标） */
    private String title;

    /** 契约版本号 */
    private String version;

    /** API 基础路径，如 /api/v1 */
    private String basePath;

    /** 端点列表 */
    private List<Endpoint> endpoints;

    /**
     * 单个 API 端点定义。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Endpoint {

        /** HTTP 方法：GET / POST / PUT / DELETE */
        private String method;

        /** 端点路径，如 /users/{id} */
        private String path;

        /** 接口摘要描述 */
        private String summary;

        /** 请求体 Schema（JSON Schema 风格 Map） */
        private Map<String, Object> requestSchema;

        /** 响应体 Schema（JSON Schema 风格 Map） */
        private Map<String, Object> responseSchema;
    }
}
