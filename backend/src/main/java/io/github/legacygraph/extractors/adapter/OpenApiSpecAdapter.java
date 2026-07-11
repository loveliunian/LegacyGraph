package io.github.legacygraph.extractors.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAPI/Swagger 规范适配器 — 从 OpenAPI 3.0 / Swagger 2.0 JSON/YAML 文件提取 API 端点与数据模型。
 * <p>
 * 解析 {@code paths} 下的每个 (path, method) 组合，创建 {@code ApiEndpoint} 类型的 KnowledgeClaimDraft；
 * 解析 {@code components/schemas}（OpenAPI 3）或 {@code definitions}（Swagger 2），创建 {@code Entity} 类型的 claim。
 * </p>
 */
@Slf4j
@Component
public class OpenApiSpecAdapter implements ExtractionAdapter {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("json", "yaml", "yml");
    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "delete", "patch", "options", "head", "trace");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Yaml yaml = new Yaml();

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        if (asset.getFileType() == null
                || !SUPPORTED_EXTENSIONS.contains(asset.getFileType().toLowerCase())) {
            return false;
        }
        String content = asset.getCachedContent();
        if (content == null) {
            if (asset.getFile() == null || !Files.isReadable(asset.getFile())) {
                return false;
            }
            try {
                content = Files.readString(asset.getFile());
            } catch (IOException e) {
                return false;
            }
        }
        // 内容须含 openapi（3.0）或 swagger（2.0）标识
        return content.contains("\"openapi\"") || content.contains("openapi:")
                || content.contains("\"swagger\"") || content.contains("swagger:");
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        List<KnowledgeClaimDraft> drafts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            String content = readContent(asset);
            if (content == null || content.isBlank()) {
                return ExtractionResult.builder()
                        .processedAssets(0)
                        .summary("OpenApiSpec: empty content for " + asset.getRelativePath())
                        .warnings(warnings)
                        .build();
            }

            Map<String, Object> spec = parseContent(content, asset.getFileType());
            if (spec == null || spec.isEmpty()) {
                return ExtractionResult.builder()
                        .processedAssets(0)
                        .summary("OpenApiSpec: failed to parse " + asset.getRelativePath())
                        .warnings(warnings)
                        .build();
            }

            String projectId = context.getProjectId();
            String versionId = context.getVersionId();
            String sourcePath = asset.getRelativePath();

            // 解析 paths → ApiEndpoint claims
            int endpointCount = extractEndpoints(spec, projectId, versionId, sourcePath, drafts);

            // 解析 schemas → Entity claims
            int entityCount = extractSchemas(spec, projectId, versionId, sourcePath, drafts);

            String summary = String.format("OpenApiSpec: %d endpoints, %d models from %s",
                    endpointCount, entityCount, sourcePath);
            log.info(summary);

            return ExtractionResult.builder()
                    .processedAssets(1)
                    .nodeCount(endpointCount + entityCount)
                    .claimDrafts(drafts)
                    .summary(summary)
                    .warnings(warnings)
                    .build();
        } catch (Exception e) {
            log.warn("OpenApiSpecAdapter failed for {}: {}", asset.getRelativePath(), e.getMessage());
            return ExtractionResult.builder()
                    .processedAssets(0)
                    .summary("OpenApiSpec failed: " + e.getMessage())
                    .warnings(warnings)
                    .build();
        }
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("OpenApiSpecAdapter")
                .languages(Set.of())
                .frameworks(Set.of("openapi", "swagger"))
                .fileTypes(SUPPORTED_EXTENSIONS)
                .aiEnhanced(false)
                .priority(10)
                .build();
    }

    // ─── 内部方法 ───

    private String readContent(SourceAsset asset) throws IOException {
        String cached = asset.getCachedContent();
        if (cached != null) {
            return cached;
        }
        if (asset.getFile() == null) {
            return null;
        }
        return Files.readString(asset.getFile());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseContent(String content, String fileType) {
        String ext = fileType != null ? fileType.toLowerCase() : "";
        try {
            if ("json".equals(ext)) {
                return objectMapper.readValue(content, Map.class);
            }
            // yaml / yml
            Object loaded = yaml.load(content);
            if (loaded instanceof Map) {
                return (Map<String, Object>) loaded;
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse OpenAPI content: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private int extractEndpoints(Map<String, Object> spec, String projectId, String versionId,
                                 String sourcePath, List<KnowledgeClaimDraft> drafts) {
        Object pathsObj = spec.get("paths");
        if (!(pathsObj instanceof Map)) {
            return 0;
        }
        Map<String, Object> paths = (Map<String, Object>) pathsObj;
        int count = 0;
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            String path = pathEntry.getKey();
            if (!(pathEntry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> pathItem = (Map<String, Object>) pathEntry.getValue();
            for (Map.Entry<String, Object> methodEntry : pathItem.entrySet()) {
                String method = methodEntry.getKey().toLowerCase();
                if (!HTTP_METHODS.contains(method)) {
                    continue;
                }
                if (!(methodEntry.getValue() instanceof Map)) {
                    continue;
                }
                Map<String, Object> operation = (Map<String, Object>) methodEntry.getValue();
                String operationId = str(operation.get("operationId"));
                String summary = str(operation.get("summary"));
                String tags = operation.get("tags") != null
                        ? operation.get("tags").toString() : null;

                Map<String, Object> qualifiers = new LinkedHashMap<>();
                qualifiers.put("method", method.toUpperCase());
                qualifiers.put("path", path);
                if (operationId != null) qualifiers.put("operationId", operationId);
                if (summary != null) qualifiers.put("summary", summary);
                if (tags != null) qualifiers.put("tags", operation.get("tags"));

                drafts.add(KnowledgeClaimDraft.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .subjectType("ApiEndpoint")
                        .subjectKey("api:" + method.toUpperCase() + " " + path)
                        .predicate("EXPOSED_AS")
                        .objectType("SourceFile")
                        .objectKey(sourcePath)
                        .objectValue(path)
                        .qualifiers(qualifiers)
                        .sourceType("SPEC")
                        .extractor("OpenApiSpecAdapter")
                        .confidence(BigDecimal.ONE)
                        .build());
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int extractSchemas(Map<String, Object> spec, String projectId, String versionId,
                               String sourcePath, List<KnowledgeClaimDraft> drafts) {
        // OpenAPI 3: components.schemas；Swagger 2: definitions
        Object schemasObj = null;
        if (spec.get("components") instanceof Map comps) {
            schemasObj = ((Map<String, Object>) comps).get("schemas");
        }
        if (schemasObj == null) {
            schemasObj = spec.get("definitions");
        }
        if (!(schemasObj instanceof Map)) {
            return 0;
        }
        Map<String, Object> schemas = (Map<String, Object>) schemasObj;
        int count = 0;
        for (Map.Entry<String, Object> schemaEntry : schemas.entrySet()) {
            String name = schemaEntry.getKey();
            if (!(schemaEntry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> schemaDef = (Map<String, Object>) schemaEntry.getValue();
            String type = str(schemaDef.get("type"));
            int propertyCount = 0;
            if (schemaDef.get("properties") instanceof Map props) {
                propertyCount = ((Map<String, Object>) props).size();
            }

            Map<String, Object> qualifiers = new LinkedHashMap<>();
            qualifiers.put("name", name);
            if (type != null) qualifiers.put("type", type);
            qualifiers.put("propertyCount", propertyCount);

            drafts.add(KnowledgeClaimDraft.builder()
                    .projectId(projectId)
                    .versionId(versionId)
                    .subjectType("Entity")
                    .subjectKey("entity:" + name)
                    .predicate("DEFINED_AS")
                    .objectType("SourceFile")
                    .objectKey(sourcePath)
                    .objectValue(type != null ? type : "object")
                    .qualifiers(qualifiers)
                    .sourceType("SPEC")
                    .extractor("OpenApiSpecAdapter")
                    .confidence(BigDecimal.ONE)
                    .build());
            count++;
        }
        return count;
    }

    private static String str(Object value) {
        return value != null ? value.toString() : null;
    }
}
