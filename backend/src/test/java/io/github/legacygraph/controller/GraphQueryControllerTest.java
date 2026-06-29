package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.service.GraphMergeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GraphQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GraphNodeRepository nodeRepository;

    private final String testProjectId = "test-project-1";
    private final String testVersionId = "version-1";

    @BeforeEach
    void setUp() {
        nodeRepository.delete(new QueryWrapper<>());
    }

    @Test
    void testGetApiChain_Success() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/graph/api-chain", testProjectId)
                        .param("versionId", testVersionId)
                        .param("api", "/api/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGetTableImpact_Success() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/graph/table-impact", testProjectId)
                        .param("versionId", testVersionId)
                        .param("tableName", "users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGetFeatureView_Success() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/graph/feature-view", testProjectId)
                        .param("versionId", testVersionId)
                        .param("module", "auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGetBusinessView_Success() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/graph/business-view", testProjectId)
                        .param("versionId", testVersionId)
                        .param("domain", "order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGetMergeCandidates_Success() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/graph/merge/candidates", testProjectId)
                        .param("nodeType", "ENTITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testDecideMerge_NodesNotFound() throws Exception {
        GraphMergeService.MergeCandidate candidate = new GraphMergeService.MergeCandidate();
        candidate.setNodeAId("node-1");
        candidate.setNodeBId("node-2");

        mockMvc.perform(post("/lg/projects/{projectId}/graph/merge/decide", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void testDecideMerge_Success() throws Exception {
        GraphNode nodeA = new GraphNode();
        nodeA.setId("node-1");
        nodeA.setProjectId(testProjectId);
        nodeA.setNodeName("NodeA");
        nodeA.setNodeType("ENTITY");
        nodeRepository.insert(nodeA);

        GraphNode nodeB = new GraphNode();
        nodeB.setId("node-2");
        nodeB.setProjectId(testProjectId);
        nodeB.setNodeName("NodeB");
        nodeB.setNodeType("ENTITY");
        nodeRepository.insert(nodeB);

        GraphMergeService.MergeCandidate candidate = new GraphMergeService.MergeCandidate();
        candidate.setNodeAId("node-1");
        candidate.setNodeBId("node-2");
        candidate.setSimilarityScore(0.85);

        mockMvc.perform(post("/lg/projects/{projectId}/graph/merge/decide", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testExecuteMerge_Success() throws Exception {
        GraphNode nodeA = new GraphNode();
        nodeA.setId("target-1");
        nodeA.setProjectId(testProjectId);
        nodeRepository.insert(nodeA);

        GraphNode nodeB = new GraphNode();
        nodeB.setId("merge-1");
        nodeB.setProjectId(testProjectId);
        nodeRepository.insert(nodeB);

        mockMvc.perform(post("/lg/projects/{projectId}/graph/merge/execute", testProjectId)
                        .param("targetNodeId", "target-1")
                        .param("mergeNodeId", "merge-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
