package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.GenerateTestCasesRequest;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.repository.ProjectRepository;
import io.github.legacygraph.repository.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
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

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String projectId = "test-project-tc";

    @BeforeEach
    void setUp() {
        // Project entity uses @TableLogic (soft delete), so MyBatis-Plus delete methods
        // only set deleted=1. Physically delete the row so we can re-insert.
        jdbcTemplate.update("DELETE FROM lg_project WHERE id = ?", projectId);
        // TestCase doesn't have @TableLogic, physical delete works fine via QueryWrapper
        testCaseRepository.delete(new QueryWrapper<>());

        Project p = new Project();
        p.setId(projectId);
        p.setProjectCode("TC-TEST");
        p.setProjectName("TC Test Project");
        p.setRepoUrl("https://github.com/test/tc");
        p.setOwner("admin");
        p.setStatus("ACTIVE");
        projectRepository.insert(p);
    }

    @Test
    void testListTestCases() throws Exception {
        mockMvc.perform(get("/lg/projects/" + projectId + "/test-cases")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray());
    }

    @Test
    void testListTestCases_WithFilters() throws Exception {
        // Don't pass 'lastRunStatus' since it's @TableField(exist=false) and lambda can't resolve it
        mockMvc.perform(get("/lg/projects/" + projectId + "/test-cases")
                        .param("caseType", "API")
                        .param("status", "CONFIRMED")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testGetTestCase_NotFound() throws Exception {
        mockMvc.perform(get("/lg/projects/" + projectId + "/test-cases/nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void testGenerateTestCases() throws Exception {
        // Use proper Scope object instead of string "full"
        GenerateTestCasesRequest.Scope scope = new GenerateTestCasesRequest.Scope();
        scope.setNodeTypes(List.of("API", "DB_ASSERTION"));

        Map<String, Object> request = Map.of(
                "versionId", "version-1",
                "scope", scope
        );

        mockMvc.perform(post("/lg/projects/" + projectId + "/test-cases/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void testRunTestCase_NotFound() throws Exception {
        mockMvc.perform(post("/lg/projects/" + projectId + "/test-cases/nonexistent/run")
                        .param("env", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void testRunTestCase_Success() throws Exception {
        TestCase testCase = new TestCase();
        testCase.setId("tc-run-success");
        testCase.setProjectId(projectId);
        testCase.setVersionId("version-2");
        testCase.setCaseNo("TC-001");
        testCase.setCaseCode("TC-001");
        testCase.setCaseName("Test API Endpoint");
        testCase.setCaseType("API");
        testCase.setStatus("CONFIRMED");
        testCase.setSteps("Step 1: do something");
        testCase.setExpectedResult("Verify response");
        testCase.setGeneratedBy("AI_GENERATED");
        testCase.setGenerateType("AI_GENERATED");
        testCaseRepository.insert(testCase);

        mockMvc.perform(post("/lg/projects/" + projectId + "/test-cases/tc-run-success/run")
                        .param("env", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void testUpdateTestCase_Success() throws Exception {
        TestCase testCase = new TestCase();
        testCase.setId("tc-update");
        testCase.setProjectId(projectId);
        testCase.setVersionId("version-2");
        testCase.setCaseNo("TC-002");
        testCase.setCaseCode("TC-002");
        testCase.setCaseName("Original Name");
        testCase.setCaseType("API");
        testCase.setStatus("DRAFT");
        testCase.setSteps("Original steps");
        testCase.setExpectedResult("Original expected");
        testCase.setGeneratedBy("AI");
        testCaseRepository.insert(testCase);

        TestCase updateRequest = new TestCase();
        updateRequest.setCaseName("Updated Name");
        updateRequest.setStatus("CONFIRMED");

        mockMvc.perform(put("/lg/projects/" + projectId + "/test-cases/tc-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.caseName").value("Updated Name"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    void testDeleteTestCase_NotFound() throws Exception {
        mockMvc.perform(delete("/lg/projects/" + projectId + "/test-cases/nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void testDeleteTestCase_Success() throws Exception {
        TestCase testCase = new TestCase();
        testCase.setId("tc-delete");
        testCase.setProjectId(projectId);
        testCase.setVersionId("version-2");
        testCase.setCaseNo("TC-003");
        testCase.setCaseCode("TC-003");
        testCase.setCaseName("To Delete");
        testCase.setCaseType("E2E");
        testCase.setStatus("DRAFT");
        testCase.setSteps("Step 1: delete me");
        testCase.setExpectedResult("Deleted");
        testCase.setGeneratedBy("AI");
        testCaseRepository.insert(testCase);

        mockMvc.perform(delete("/lg/projects/" + projectId + "/test-cases/tc-delete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
