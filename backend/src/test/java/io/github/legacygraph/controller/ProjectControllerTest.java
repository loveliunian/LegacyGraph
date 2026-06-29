package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.CreateProjectRequest;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @BeforeEach
    void setUp() {
        // hard-delete all rows so unique constraints don't clash with soft-deleted data
        projectRepository.delete(new QueryWrapper<>());
    }

    @Test
    @Order(1)
    void testListProjects() throws Exception {
        // Use unique project codes per method invocation
        long ts = System.currentTimeMillis();
        for (int i = 1; i <= 3; i++) {
            Project project = new Project();
            project.setId("proj-" + i + "-" + ts);
            project.setProjectCode("PROJ-" + i + "-" + ts);
            project.setProjectName("Test Project " + i);
            project.setRepoUrl("https://github.com/test/repo" + i);
            project.setStatus("ACTIVE");
            project.setOwner("admin");
            projectRepository.insert(project);
        }

        mockMvc.perform(get("/lg/projects")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total").value(3));
    }

    @Test
    @Order(2)
    void testCreateProject_Success() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setProjectCode("NP-001");
        request.setProjectName("New Project");
        request.setRepoUrl("https://github.com/test/new-project");
        request.setDescription("Test description");

        mockMvc.perform(post("/lg/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    @Order(3)
    void testCreateProject_MissingName() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setProjectCode("NO-NAME");
        request.setRepoUrl("https://github.com/test/new-project");

        mockMvc.perform(post("/lg/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(4)
    void testDeleteProject_Success() throws Exception {
        String id = "proj-to-delete";
        Project project = new Project();
        project.setId(id);
        project.setProjectCode("DLT-" + System.currentTimeMillis());
        project.setProjectName("To Delete");
        project.setRepoUrl("https://github.com/test/delete");
        project.setStatus("ACTIVE");
        project.setOwner("admin");
        projectRepository.insert(project);

        mockMvc.perform(delete("/lg/projects/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(5)
    void testDeleteProject_NotFound() throws Exception {
        mockMvc.perform(delete("/lg/projects/nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
}
