package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LlmAgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testRunAgent_UnknownType() throws Exception {
        LlmAgentController.RunAgentRequest request = new LlmAgentController.RunAgentRequest();
        request.setAgentType("unknown");
        request.setProjectId("project-1");
        request.setVariables(Map.of());

        mockMvc.perform(post("/agents/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void testRunAgent_CodeFact() throws Exception {
        LlmAgentController.RunAgentRequest request = new LlmAgentController.RunAgentRequest();
        request.setAgentType("codefact");
        request.setProjectId("project-1");
        request.setVariables(Map.of(
                "codeContent", "public class Test { }",
                "sourcePath", "src/Test.java"
        ));

        mockMvc.perform(post("/agents/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testRunAgent_DocUnderstanding() throws Exception {
        LlmAgentController.RunAgentRequest request = new LlmAgentController.RunAgentRequest();
        request.setAgentType("docunderstanding");
        request.setProjectId("project-1");
        request.setVariables(Map.of(
                "docContent", "This is a business document.",
                "sourcePath", "docs/readme.md"
        ));

        mockMvc.perform(post("/agents/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testRunAgent_FeatureMapping() throws Exception {
        LlmAgentController.RunAgentRequest request = new LlmAgentController.RunAgentRequest();
        request.setAgentType("featuremapping");
        request.setProjectId("project-1");
        request.setVariables(Map.of(
                "vueCode", "<template></template>",
                "apiDefinitions", "[]",
                "controllerCode", "public class Controller { }",
                "permissionInfo", "admin",
                "productDoc", "Product requirements"
        ));

        mockMvc.perform(post("/agents/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testRunAgent_TestCaseGeneration() throws Exception {
        LlmAgentController.RunAgentRequest request = new LlmAgentController.RunAgentRequest();
        request.setAgentType("testcasegeneration");
        request.setProjectId("project-1");
        request.setVariables(Map.of(
                "featureKey", "login",
                "featureName", "User Login",
                "apiEndpoint", "/api/auth/login",
                "httpMethod", "POST",
                "requestSchema", "{username: string, password: string}",
                "relatedTables", "users",
                "businessRules", "Valid credentials required"
        ));

        mockMvc.perform(post("/agents/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testGenerateTests_Success() throws Exception {
        io.github.legacygraph.agent.TestCaseAgent.TestGenerationRequest request =
                new io.github.legacygraph.agent.TestCaseAgent.TestGenerationRequest();
        request.setProjectId("project-1");
        request.setFeatureKey("login");
        request.setFeatureName("User Login");
        request.setApiEndpoint("/api/auth/login");
        request.setHttpMethod("POST");

        mockMvc.perform(post("/agents/tests/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testSuggestReview_Success() throws Exception {
        io.github.legacygraph.agent.ReviewAgent.ReviewRequest request =
                new io.github.legacygraph.agent.ReviewAgent.ReviewRequest();
        request.setProjectId("project-1");
        request.setTargetType("NODE");
        request.setTargetDescription("Test fact content");
        request.setSupportingEvidence(List.of("Evidence 1"));
        request.setConflictingEvidence(List.of());
        request.setCurrentConfidence(0.8);

        mockMvc.perform(post("/agents/review/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
