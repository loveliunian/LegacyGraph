package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.repository.ProjectRepository;
import io.github.legacygraph.service.GraphMergeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String ts = String.valueOf(System.currentTimeMillis());
    private final String testProjectId = "test-project-graph-" + ts;
    private final String testVersionId = "version-1";

    @BeforeEach
    void setUp() {
        // Project entity uses @TableLogic (soft delete), so MyBatis-Plus delete methods
        // only set deleted=1. Physically delete the row so we can re-insert.
        jdbcTemplate.update("DELETE FROM lg_project WHERE id = ?", testProjectId);
        // GraphNode doesn't have @TableLogic, physical delete works fine via QueryWrapper
        nodeRepository.delete(new QueryWrapper<>());

        Project p = new Project();
        p.setId(testProjectId);
        p.setProjectCode("GRAPH-TEST-" + ts);
        p.setProjectName("Graph Test Project");
        p.setRepoUrl("https://github.com/test/graph");
        p.setOwner("admin");
        p.setStatus("ACTIVE");
        projectRepository.insert(p);
    }

    @Test
    void testGetApiChain_Success() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/graph/api-chain", testProjectId)
                        .param("versionId", testVersionId)
                        .param("api", "/api/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void testGetTableImpact_Success() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/graph/table-impact", testProjectId)
                        .param("versionId", testVersionId)
                        .param("tableName", "users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void testGetFeatureView_Success() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/graph/feature-view", testProjectId)
                        .param("versionId", testVersionId)
                        .param("module", "auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void testGetBusinessView_Success() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/graph/business-view", testProjectId)
                        .param("versionId", testVersionId)
                        .param("domain", "order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void testGetMergeCandidates_Success() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/graph/merge/candidates", testProjectId)
                        .param("nodeType", "ENTITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testDecideMerge_NodesNotFound() throws Exception {
        GraphMergeService.MergeCandidate candidate = new GraphMergeService.MergeCandidate();
        candidate.setNodeAId("node-decide-a-" + ts);
        candidate.setNodeBId("node-decide-b-" + ts);

        mockMvc.perform(post("/lg/projects/{projectId}/graph/merge/decide", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void testDecideMerge_Success() throws Exception {
        GraphNode nodeA = new GraphNode();
        nodeA.setId("node-a-" + ts);
        nodeA.setProjectId(testProjectId);
        nodeA.setVersionId("version-1");
        nodeA.setNodeName("NodeA");
        nodeA.setNodeType("ENTITY");
        nodeA.setNodeKey("node-key-a-" + ts);
        nodeRepository.insert(nodeA);

        GraphNode nodeB = new GraphNode();
        nodeB.setId("node-b-" + ts);
        nodeB.setProjectId(testProjectId);
        nodeB.setVersionId("version-1");
        nodeB.setNodeName("NodeB");
        nodeB.setNodeType("ENTITY");
        nodeB.setNodeKey("node-key-b-" + ts);
        nodeRepository.insert(nodeB);

        GraphMergeService.MergeCandidate candidate = new GraphMergeService.MergeCandidate();
        candidate.setNodeAId(nodeA.getId());
        candidate.setNodeBId(nodeB.getId());
        candidate.setSimilarityScore(0.85);

        mockMvc.perform(post("/lg/projects/{projectId}/graph/merge/decide", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testExecuteMerge_Success() throws Exception {
        GraphNode nodeA = new GraphNode();
        nodeA.setId("target-" + ts);
        nodeA.setProjectId(testProjectId);
        nodeA.setVersionId("version-1");
        nodeA.setNodeType("ENTITY");
        nodeA.setNodeKey("target-key-" + ts);
        nodeA.setNodeName("TargetNode");
        nodeRepository.insert(nodeA);

        GraphNode nodeB = new GraphNode();
        nodeB.setId("merge-" + ts);
        nodeB.setProjectId(testProjectId);
        nodeB.setVersionId("version-1");
        nodeB.setNodeType("ENTITY");
        nodeB.setNodeKey("merge-key-" + ts);
        nodeB.setNodeName("MergeNode");
        nodeRepository.insert(nodeB);

        mockMvc.perform(post("/lg/projects/{projectId}/graph/merge/execute", testProjectId)
                        .param("targetNodeId", nodeA.getId())
                        .param("mergeNodeId", nodeB.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
