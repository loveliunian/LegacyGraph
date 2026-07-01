package io.github.legacygraph.agent;

import io.github.legacygraph.llm.LlmGateway;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FeatureMappingAgent - 功能映射对齐
 *
 * 职责：
 * - 将页面、按钮、前端 API 调用与后端接口对齐
 * - 将权限点与业务动作对齐
 * - 建立从 UI 到 API 到 Service 的完整链路
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureMappingAgent {

    private final LlmGateway llmGateway;

    /**
     * 功能映射请求
     */
    @Data
    public static class MappingRequest {
        private String projectId;
        private String vueCode;        // Vue 页面组件代码
        private String apiDefinitions; // 前端 API 调用定义
        private String controllerCode; // Spring Controller 接口
        private String permissionInfo;  // 权限注解信息
        private String productDoc;      // 产品文档功能清单
    }

    /**
     * 功能映射结果
     */
    @Data
    public static class MappingResult {
        private List<Mapping> mappings = new ArrayList<>();
        private List<String> unmatched = new ArrayList<>();
    }

    @Data
    public static class Mapping {
        private String pageKey;
        private String buttonName;
        private String apiKey;
        private String businessAction;
        private String permissionKey;
        private double confidence;
        private List<Map<String, Object>> evidence;
        private List<String> conflicts;
    }

    /**
     * 执行功能映射
     */
    public MappingResult mapFeatures(MappingRequest request) {
        Map<String, String> variables = new HashMap<>();
        variables.put("vueCode", request.getVueCode() != null ? request.getVueCode() : "");
        variables.put("apiDefinitions", request.getApiDefinitions() != null ? request.getApiDefinitions() : "");
        variables.put("controllerCode", request.getControllerCode() != null ? request.getControllerCode() : "");
        variables.put("permissionInfo", request.getPermissionInfo() != null ? request.getPermissionInfo() : "");
        variables.put("productDoc", request.getProductDoc() != null ? request.getProductDoc() : "");

        return llmGateway.callWithTemplate(request.getProjectId(), "feature-mapping",
                variables, MappingResult.class);
    }
}
