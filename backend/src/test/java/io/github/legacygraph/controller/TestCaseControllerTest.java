package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.repository.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TestCaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestCaseRepository testCaseRepository;

    private final String projectId = "test-project";

    @BeforeEach
    void setUp() {
        testCaseRepository.delete(null);
    }

    @Test
    void testListTestCases() throws Exception {
        mockMvc.perform(get("/lg/projects/" + projectId + "/test-cases")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list").isArray());
    }

    @Test
    void testListTestCases_WithFilters() throws Exception {
        mockMvc.perform(get("/lg/projects/" + projectId + "/test-cases")
                        .param("caseType", "API")
                        .param("status", "CONFIRMED")
                        .param("lastRunStatus", "PASSED")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGetTestCase_NotFound() throws Exception {
        mockMvc.perform(get("/lg/projects/" + projectId + "/test-cases/nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void testGenerateTestCases() throws Exception {
        Map<String, Object> request = Map.of(
                "scope", "full",
                "includeTypes", new String[]{"API", "DB_ASSERTION"}
        );

        mockMvc.perform(post("/lg/projects/" + projectId + "/test-cases/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.caseCount").exists())
                .andExpect(jsonPath("$.data.taskId").exists());
    }

    @Test
    void testRunTestCase_NotFound() throws Exception {
        mockMvc.perform(post("/lg/projects/" + projectId + "/test-cases/nonexistent/run")
                        .param("env", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void testRunTestCase_Success() throws Exception {
        TestCase testCase = new TestCase();
        testCase.setId("tc-1");
        testCase.setProjectId(projectId);
        testCase.setCaseNo("TC-001");
        testCase.setCaseName("Test API Endpoint");
        testCase.setCaseType("API");
        testCase.setStatus("CONFIRMED");
        testCase.setGenerateType("AI_GENERATED");
        testCaseRepository.insert(testCase);

        mockMvc.perform(post("/lg/projects/" + projectId + "/test-cases/tc-1/run")
                        .param("env", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.runId").exists())
                .andExpect(jsonPath("$.data.status").exists());
    }

    @Test
    void testUpdateTestCase_Success() throws Exception {
        TestCase testCase = new TestCase();
        testCase.setId("tc-2");
        testCase.setProjectId(projectId);
        testCase.setCaseNo("TC-002");
        testCase.setCaseName("Original Name");
        testCase.setCaseType("API");
        testCase.setStatus("DRAFT");
        testCaseRepository.insert(testCase);

        TestCase updateRequest = new TestCase();
        updateRequest.setCaseName("Updated Name");
        updateRequest.setStatus("CONFIRMED");

        mockMvc.perform(put("/lg/projects/" + projectId + "/test-cases/tc-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.caseName").value("Updated Name"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    void testDeleteTestCase_NotFound() throws Exception {
        mockMvc.perform(delete("/lg/projects/" + projectId + "/test-cases/nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void testDeleteTestCase_Success() throws Exception {
        TestCase testCase = new TestCase();
        testCase.setId("tc-3");
        testCase.setProjectId(projectId);
        testCase.setCaseNo("TC-003");
        testCase.setCaseName("To Delete");
        testCase.setCaseType("E2E");
        testCase.setStatus("DRAFT");
        testCaseRepository.insert(testCase);

        mockMvc.perform(delete("/lg/projects/" + projectId + "/test-cases/tc-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
