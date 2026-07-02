package io.github.legacygraph.agent;

import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.llm.LlmGateway;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * FeatureMappingAgent - 功能映射对齐。
 * Phase 3-1: {@link #mapFeaturesFromEnvelope(AgentEnvelope)} 合约入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureMappingAgent {

    private final LlmGateway llmGateway;

    @Data
    public static class MappingRequest {
        private String projectId;
        private String vueCode;
        private String apiDefinitions;
        private String controllerCode;
        private String permissionInfo;
        private String productDoc;
    }

    @Data
    public static class MappingResult {
        private List<Mapping> mappings = new ArrayList<>();
        private List<String> unmatched = new ArrayList<>();
    }

    @Data
    public static class Mapping {
        private String pageKey, buttonName, apiKey, businessAction, permissionKey;
        private double confidence;
        private List<Map<String, Object>> evidence;
        private List<String> conflicts;
    }

    /** Phase 3-1: AgentEnvelope 合约入口 */
    public MappingResult mapFeaturesFromEnvelope(AgentEnvelope<MappingRequest> env) {
        MappingRequest request = env.getInput();
        if (request == null) {
            return null;
        }
        Map<String, String> variables = buildVariables(request);
        return llmGateway.callWithEnvelope(env, "feature-mapping",
                variables, MappingResult.class);
    }

    public MappingResult mapFeatures(MappingRequest request) {
        Map<String, String> variables = buildVariables(request);
        return llmGateway.callWithTemplate(request.getProjectId(), "feature-mapping",
                variables, MappingResult.class);
    }

    /**
     * 将功能映射结果转换为 Claim 草稿：Feature 入口、Feature 实现、权限要求。
     */
    public List<KnowledgeClaimDraft> toClaimDrafts(String projectId, String versionId,
                                                   MappingResult result) {
        List<KnowledgeClaimDraft> drafts = new ArrayList<>();
        if (result == null || result.getMappings() == null) {
            return drafts;
        }
        for (Mapping mapping : result.getMappings()) {
            if (mapping == null) {
                continue;
            }
            String featureKey = featureKey(mapping);
            BigDecimal confidence = BigDecimal.valueOf(normalizeConfidence(mapping.getConfidence()));
            if (hasText(featureKey) && hasText(mapping.getPageKey())) {
                drafts.add(mappingDraft(projectId, versionId, "Feature", featureKey,
                        "EXPOSED_BY", "Page", mapping.getPageKey(), confidence));
            }
            if (hasText(featureKey) && hasText(mapping.getApiKey())) {
                drafts.add(mappingDraft(projectId, versionId, "Feature", featureKey,
                        "IMPLEMENTS", "ApiEndpoint", mapping.getApiKey(), confidence));
            }
            if (hasText(mapping.getApiKey()) && hasText(mapping.getPermissionKey())) {
                drafts.add(mappingDraft(projectId, versionId, "ApiEndpoint", mapping.getApiKey(),
                        "REQUIRES_PERMISSION", "Permission", mapping.getPermissionKey(), confidence));
            }
        }
        return drafts;
    }

    private Map<String, String> buildVariables(MappingRequest request) {
        Map<String, String> variables = new HashMap<>();
        variables.put("vueCode", nz(request.getVueCode()));
        variables.put("apiDefinitions", nz(request.getApiDefinitions()));
        variables.put("controllerCode", nz(request.getControllerCode()));
        variables.put("permissionInfo", nz(request.getPermissionInfo()));
        variables.put("productDoc", nz(request.getProductDoc()));
        return variables;
    }

    private String nz(String s) { return s != null ? s : ""; }

    private KnowledgeClaimDraft mappingDraft(String projectId, String versionId,
                                             String subjectType, String subjectKey,
                                             String predicate, String objectType, String objectKey,
                                             BigDecimal confidence) {
        return KnowledgeClaimDraft.builder()
                .projectId(projectId)
                .versionId(versionId)
                .subjectType(subjectType)
                .subjectKey(subjectKey)
                .predicate(predicate)
                .objectType(objectType)
                .objectKey(objectKey)
                .sourceType("AI_INFERENCE")
                .extractor("FeatureMappingAgent")
                .confidence(confidence)
                .build();
    }

    private String featureKey(Mapping mapping) {
        if (hasText(mapping.getBusinessAction())) {
            return "feature:" + mapping.getBusinessAction();
        }
        if (hasText(mapping.getApiKey())) {
            return "feature:" + mapping.getApiKey();
        }
        if (hasText(mapping.getPageKey())) {
            return "feature:" + mapping.getPageKey();
        }
        return null;
    }

    private double normalizeConfidence(double confidence) {
        return confidence > 0 ? Math.min(1.0, Math.max(0.0, confidence)) : 0.7;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
