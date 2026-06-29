package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
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

    @Autowired
    private GraphNodeRepository nodeRepository;

    @BeforeEach
    void setUp() {
        nodeRepository.delete(new QueryWrapper<>());
    }

    @Test
    void testRunAgent_UnknownType() throws Exception {
        LlmAgentController.RunAgentRequest request = new LlmAgentController.RunAgentRequest();
        request.setAgentType("unknown");
        request.setProjectId("project-1");
        request.setVariables(Map.of());

        mockMvc.perform(post("/api/agents/run")
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

        mockMvc.perform(post("/api/agents/run")
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

        mockMvc.perform(post("/api/agents/run")
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

        mockMvc.perform(post("/api/agents/run")
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

        mockMvc.perform(post("/api/agents/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testGetMergeCandidates_Success() throws Exception {
        mockMvc.perform(get("/api/agents/graph/merge/candidates")
                        .param("projectId", "project-1")
                        .param("nodeType", "ENTITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testDecideMerge_NodesNotFound() throws Exception {
        mockMvc.perform(post("/api/agents/graph/merge/decide")
                        .param("projectId", "project-1")
                        .param("nodeAId", "node-1")
                        .param("nodeBId", "node-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void testDecideMerge_Success() throws Exception {
        GraphNode nodeA = new GraphNode();
        nodeA.setId("merge-node-a");
        nodeA.setProjectId("project-1");
        nodeA.setVersionId("version-2");
        nodeA.setNodeName("NodeA");
        nodeA.setNodeType("ENTITY");
        nodeA.setNodeKey("node-key-a");
        nodeRepository.insert(nodeA);

        GraphNode nodeB = new GraphNode();
        nodeB.setId("merge-node-b");
        nodeB.setProjectId("project-1");
        nodeB.setVersionId("version-2");
        nodeB.setNodeName("NodeB");
        nodeB.setNodeType("ENTITY");
        nodeB.setNodeKey("node-key-b");
        nodeRepository.insert(nodeB);

        mockMvc.perform(post("/api/agents/graph/merge/decide")
                        .param("projectId", "project-1")
                        .param("nodeAId", "merge-node-a")
                        .param("nodeBId", "merge-node-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testExecuteMerge_Success() throws Exception {
        mockMvc.perform(post("/api/agents/graph/merge/execute")
                        .param("projectId", "project-1")
                        .param("targetNodeId", "target-1")
                        .param("mergeNodeId", "merge-1"))
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

        mockMvc.perform(post("/api/agents/tests/generate")
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

        mockMvc.perform(post("/api/agents/review/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
