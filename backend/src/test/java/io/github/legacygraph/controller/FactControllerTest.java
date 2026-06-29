package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.Evidence;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.EvidenceRepository;
import io.github.legacygraph.repository.ProjectRepository;
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
class FactControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FactRepository factRepository;

    @Autowired
    private EvidenceRepository evidenceRepository;

    @Autowired
    private ProjectRepository projectRepository;

    // Use unique IDs per test run to avoid PK conflicts from soft-delete
    private static final String TS = String.valueOf(System.currentTimeMillis());
    private final String testProjectId = "test-project-fact-" + TS;
    private final String wrongProjectId = "wrong-project-fact-" + TS;

    @BeforeEach
    void setUp() {
        evidenceRepository.delete(new QueryWrapper<>());
        factRepository.delete(new QueryWrapper<>());
        projectRepository.delete(new QueryWrapper<>());

        createProject(testProjectId, "FACT-TEST-" + TS, "Fact Test Project");
        createProject(wrongProjectId, "WRONG-PROJ-" + TS, "Wrong Project");
    }

    private void createProject(String id, String code, String name) {
        Project p = new Project();
        p.setId(id);
        p.setProjectCode(code);
        p.setProjectName(name);
        p.setRepoUrl("https://github.com/test/" + code.toLowerCase());
        p.setOwner("admin");
        p.setStatus("ACTIVE");
        try {
            projectRepository.insert(p);
        } catch (Exception e) {
            // already exists — ignore (soft-delete may leave the row)
        }
    }

    @Test
    void testListFacts_Empty() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/facts", testProjectId)
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").exists());
    }

    @Test
    void testListFacts_WithData() throws Exception {
        Fact fact = new Fact();
        fact.setId("fact-1");
        fact.setProjectId(testProjectId);
        fact.setVersionId("version-1");
        fact.setFactType("BUSINESS_RULE");
        fact.setFactKey("business-rule-key");
        fact.setSourceType("CODE");
        fact.setNormalizedData("test data");
        fact.setConfidence(0.95);
        factRepository.insert(fact);

        mockMvc.perform(get("/lg/projects/{projectId}/facts", testProjectId)
                        .param("pageNum", "1")
                        .param("pageSize", "10")
                        .param("factType", "BUSINESS_RULE")
                        .param("minConfidence", "0.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void testGetFact_Success() throws Exception {
        Fact fact = new Fact();
        fact.setId("fact-get-success");
        fact.setProjectId(testProjectId);
        fact.setVersionId("version-1");
        fact.setFactType("BUSINESS_RULE");
        fact.setFactKey("business-rule-key");
        fact.setSourceType("CODE");
        fact.setNormalizedData("test data");
        fact.setConfidence(1.0);
        factRepository.insert(fact);

        mockMvc.perform(get("/lg/projects/{projectId}/facts/{id}", testProjectId, "fact-get-success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("fact-get-success"));
    }

    @Test
    void testGetFact_NotFound() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/facts/{id}", testProjectId, "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void testGetFact_WrongProject() throws Exception {
        Fact fact = new Fact();
        fact.setId("fact-wrong-project");
        fact.setProjectId(wrongProjectId);
        fact.setVersionId("version-1");
        fact.setFactType("BUSINESS_RULE");
        fact.setFactKey("business-rule-key");
        fact.setSourceType("CODE");
        fact.setNormalizedData("test data");
        fact.setConfidence(1.0);
        factRepository.insert(fact);

        mockMvc.perform(get("/lg/projects/{projectId}/facts/{id}", testProjectId, "fact-wrong-project"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void testGetRelatedNodes() throws Exception {
        Fact fact = new Fact();
        fact.setId("fact-related-nodes");
        fact.setProjectId(testProjectId);
        fact.setVersionId("version-1");
        fact.setFactType("BUSINESS_RULE");
        fact.setFactKey("business-rule-key");
        fact.setSourceType("CODE");
        fact.setNormalizedData("test data");
        fact.setConfidence(1.0);
        factRepository.insert(fact);

        mockMvc.perform(get("/lg/projects/{projectId}/facts/{id}/related-nodes", testProjectId, "fact-related-nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testSearchEvidence_Empty() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/evidence", testProjectId)
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testGetEvidence_Success() throws Exception {
        Evidence evidence = new Evidence();
        evidence.setId("evidence-success");
        evidence.setProjectId(testProjectId);
        evidence.setVersionId("version-1");
        evidence.setEvidenceType("CODE");
        evidenceRepository.insert(evidence);

        mockMvc.perform(get("/lg/projects/{projectId}/evidence/{id}", testProjectId, "evidence-success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("evidence-success"));
    }

    @Test
    void testGetEvidence_NotFound() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/evidence/{id}", testProjectId, "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void testExtractCodeFacts() throws Exception {
        FactController.ExtractCodeFactsRequest request = new FactController.ExtractCodeFactsRequest();
        request.setRepoId("repo-1");
        request.setFilePath("src/Test.java");
        request.setContent("public class Test { }");

        mockMvc.perform(post("/lg/projects/{projectId}/extract/facts/code", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testExtractDocFacts() throws Exception {
        FactController.ExtractDocFactsRequest request = new FactController.ExtractDocFactsRequest();
        request.setDocId("doc-1");
        request.setContent("This is a test document.");

        mockMvc.perform(post("/lg/projects/{projectId}/extract/facts/doc", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
