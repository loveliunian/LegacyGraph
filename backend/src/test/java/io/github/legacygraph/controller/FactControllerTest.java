package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.Evidence;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.EvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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

    private final String testProjectId = "test-project-1";

    @BeforeEach
    void setUp() {
        factRepository.delete(null);
        evidenceRepository.delete(null);
    }

    @Test
    void testListFacts_Empty() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/facts", testProjectId)
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list").exists());
    }

    @Test
    void testListFacts_WithData() throws Exception {
        Fact fact = new Fact();
        fact.setId("fact-1");
        fact.setProjectId(testProjectId);
        fact.setFactType("BUSINESS_RULE");
        fact.setSourceType("CODE");
        fact.setConfidence(0.95);
        factRepository.insert(fact);

        mockMvc.perform(get("/lg/projects/{projectId}/facts", testProjectId)
                        .param("pageNum", "1")
                        .param("pageSize", "10")
                        .param("factType", "BUSINESS_RULE")
                        .param("minConfidence", "0.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void testGetFact_Success() throws Exception {
        Fact fact = new Fact();
        fact.setId("fact-1");
        fact.setProjectId(testProjectId);
        fact.setFactType("BUSINESS_RULE");
        factRepository.insert(fact);

        mockMvc.perform(get("/lg/projects/{projectId}/facts/{id}", testProjectId, "fact-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("fact-1"));
    }

    @Test
    void testGetFact_NotFound() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/facts/{id}", testProjectId, "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void testGetFact_WrongProject() throws Exception {
        Fact fact = new Fact();
        fact.setId("fact-1");
        fact.setProjectId("wrong-project");
        factRepository.insert(fact);

        mockMvc.perform(get("/lg/projects/{projectId}/facts/{id}", testProjectId, "fact-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void testGetRelatedNodes() throws Exception {
        Fact fact = new Fact();
        fact.setId("fact-1");
        fact.setProjectId(testProjectId);
        factRepository.insert(fact);

        mockMvc.perform(get("/lg/projects/{projectId}/facts/{id}/related-nodes", testProjectId, "fact-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testSearchEvidence_Empty() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/evidence", testProjectId)
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGetEvidence_Success() throws Exception {
        Evidence evidence = new Evidence();
        evidence.setId("evidence-1");
        evidence.setProjectId(testProjectId);
        evidence.setEvidenceType("CODE");
        evidenceRepository.insert(evidence);

        mockMvc.perform(get("/lg/projects/{projectId}/evidence/{id}", testProjectId, "evidence-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("evidence-1"));
    }

    @Test
    void testGetEvidence_NotFound() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/evidence/{id}", testProjectId, "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
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
                .andExpect(jsonPath("$.code").value(200));
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
                .andExpect(jsonPath("$.code").value(200));
    }
}
