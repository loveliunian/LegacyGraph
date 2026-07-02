package io.github.legacygraph.agent;

import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.llm.LlmGateway;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
        return mapFeatures(env.getInput());
    }

    public MappingResult mapFeatures(MappingRequest request) {
        Map<String, String> variables = new HashMap<>();
        variables.put("vueCode", nz(request.getVueCode()));
        variables.put("apiDefinitions", nz(request.getApiDefinitions()));
        variables.put("controllerCode", nz(request.getControllerCode()));
        variables.put("permissionInfo", nz(request.getPermissionInfo()));
        variables.put("productDoc", nz(request.getProductDoc()));
        return llmGateway.callWithTemplate(request.getProjectId(), "feature-mapping",
                variables, MappingResult.class);
    }

    private String nz(String s) { return s != null ? s : ""; }
}
