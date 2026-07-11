package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenApiSpecAdapter 单元测试。
 * 验证从 OpenAPI 3.0 JSON / Swagger 2.0 YAML 提取 API 端点与数据模型，以及 supports 判断。
 */
@ExtendWith(MockitoExtension.class)
class OpenApiSpecAdapterTest {

    @TempDir
    Path tempDir;

    private final OpenApiSpecAdapter adapter = new OpenApiSpecAdapter();

    private static final String OPENAPI_3_JSON = """
            {
              "openapi": "3.0.0",
              "info": { "title": "Demo API", "version": "1.0.0" },
              "paths": {
                "/users": {
                  "get": { "operationId": "listUsers", "summary": "List all users" },
                  "post": { "operationId": "createUser", "summary": "Create a user" }
                },
                "/users/{id}": {
                  "delete": { "operationId": "deleteUser", "summary": "Delete a user" }
                }
              },
              "components": {
                "schemas": {
                  "User": {
                    "type": "object",
                    "properties": {
                      "id": { "type": "integer" },
                      "name": { "type": "string" }
                    }
                  }
                }
              }
            }
            """;

    private static final String SWAGGER_2_YAML = """
            swagger: "2.0"
            info:
              title: Legacy API
              version: "1.0"
            paths:
              /orders:
                get:
                  operationId: listOrders
                  summary: List all orders
                post:
                  operationId: createOrder
                  summary: Create an order
              /orders/{id}:
                put:
                  operationId: updateOrder
                  summary: Update an order
            definitions:
              Order:
                type: object
                properties:
                  id:
                    type: integer
                  amount:
                    type: number
            """;

    /**
     * 测试从 OpenAPI 3.0 JSON 提取 ApiEndpoint claims。
     */
    @Test
    void extractOpenApi3JsonCreatesApiEndpointClaims() throws Exception {
        Path file = tempDir.resolve("openapi.json");
        Files.writeString(file, OPENAPI_3_JSON);
        ScanContext ctx = ScanContext.builder().projectId("p1").versionId("v1").build();
        SourceAsset asset = SourceAsset.builder()
                .file(file)
                .relativePath("openapi.json")
                .fileType("json")
                .build();

        ExtractionResult result = adapter.extract(ctx, asset);

        assertNotNull(result);
        assertEquals(1, result.getProcessedAssets());
        List<KnowledgeClaimDraft> drafts = result.getClaimDrafts();
        assertNotNull(drafts);
        assertFalse(drafts.isEmpty());

        // 3 个端点 + 1 个 schema
        long endpointClaims = drafts.stream()
                .filter(d -> "ApiEndpoint".equals(d.getSubjectType()))
                .count();
        assertEquals(3, endpointClaims, "Should extract 3 API endpoints");

        long entityClaims = drafts.stream()
                .filter(d -> "Entity".equals(d.getSubjectType()))
                .count();
        assertEquals(1, entityClaims, "Should extract 1 schema entity");

        // 验证端点细节
        KnowledgeClaimDraft getEndpoint = drafts.stream()
                .filter(d -> "api:GET /users".equals(d.getSubjectKey()))
                .findFirst()
                .orElseThrow();
        assertEquals("ApiEndpoint", getEndpoint.getSubjectType());
        assertEquals("EXPOSED_AS", getEndpoint.getPredicate());
        assertEquals("listUsers", getEndpoint.getQualifiers().get("operationId"));
    }

    /**
     * 测试从 Swagger 2.0 YAML 提取 ApiEndpoint claims。
     */
    @Test
    void extractSwagger2YamlCreatesApiEndpointClaims() throws Exception {
        Path file = tempDir.resolve("swagger.yaml");
        Files.writeString(file, SWAGGER_2_YAML);
        ScanContext ctx = ScanContext.builder().projectId("p1").versionId("v1").build();
        SourceAsset asset = SourceAsset.builder()
                .file(file)
                .relativePath("swagger.yaml")
                .fileType("yaml")
                .build();

        ExtractionResult result = adapter.extract(ctx, asset);

        assertNotNull(result);
        assertEquals(1, result.getProcessedAssets());
        List<KnowledgeClaimDraft> drafts = result.getClaimDrafts();
        assertNotNull(drafts);
        assertFalse(drafts.isEmpty());

        long endpointClaims = drafts.stream()
                .filter(d -> "ApiEndpoint".equals(d.getSubjectType()))
                .count();
        assertEquals(3, endpointClaims, "Should extract 3 API endpoints from Swagger YAML");

        long entityClaims = drafts.stream()
                .filter(d -> "Entity".equals(d.getSubjectType()))
                .count();
        assertEquals(1, entityClaims, "Should extract 1 schema entity from definitions");

        // 验证 PUT 端点
        KnowledgeClaimDraft putEndpoint = drafts.stream()
                .filter(d -> "api:PUT /orders/{id}".equals(d.getSubjectKey()))
                .findFirst()
                .orElseThrow();
        assertEquals("updateOrder", putEndpoint.getQualifiers().get("operationId"));
    }

    /**
     * 测试 supports — JSON 文件含 openapi 字段时返回 true。
     */
    @Test
    void supportsReturnsTrueForOpenApiJson() throws Exception {
        Path file = tempDir.resolve("spec.json");
        Files.writeString(file, OPENAPI_3_JSON);
        ScanContext ctx = ScanContext.builder().build();
        SourceAsset asset = SourceAsset.builder()
                .file(file)
                .relativePath("spec.json")
                .fileType("json")
                .build();

        assertTrue(adapter.supports(ctx, asset));
    }

    /**
     * 测试 supports — 普通 JSON（不含 openapi/swagger 标识）返回 false。
     */
    @Test
    void supportsReturnsFalseForRegularJson() throws Exception {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, "{ \"name\": \"app\", \"port\": 8080 }");
        ScanContext ctx = ScanContext.builder().build();
        SourceAsset asset = SourceAsset.builder()
                .file(file)
                .relativePath("config.json")
                .fileType("json")
                .build();

        assertFalse(adapter.supports(ctx, asset));
    }

    /**
     * 测试 capability 返回正确的能力描述。
     */
    @Test
    void capabilityReturnsCorrectCapability() {
        AdapterCapability cap = adapter.capability();

        assertEquals("OpenApiSpecAdapter", cap.getName());
        assertEquals(Set.of("json", "yaml", "yml"), cap.getFileTypes());
        assertEquals(Set.of("openapi", "swagger"), cap.getFrameworks());
        assertEquals(10, cap.getPriority());
        assertFalse(cap.isAiEnhanced());
    }
}
