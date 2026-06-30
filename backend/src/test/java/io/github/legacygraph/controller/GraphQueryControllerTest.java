package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.repository.ProjectRepository;
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
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String ts = String.valueOf(System.currentTimeMillis());
    private final String testProjectId = "test-project-graph-" + ts;
    private final String testVersionId = "version-1";

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM lg_project WHERE id = ?", testProjectId);

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
        mockMvc.perform(post("/lg/projects/{projectId}/graph/merge/decide", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeAId\":\"nonexistent-a\",\"nodeBId\":\"nonexistent-b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void testExecuteMerge_Success() throws Exception {
        mockMvc.perform(post("/lg/projects/{projectId}/graph/merge/execute", testProjectId)
                        .param("targetNodeId", "target-1")
                        .param("mergeNodeId", "merge-1"))
                .andExpect(status().isOk());
    }
}
