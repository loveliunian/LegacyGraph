package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.CreateProjectRequest;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.repository.ProjectRepository;
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
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @BeforeEach
    void setUp() {
        projectRepository.delete(null);
    }

    @Test
    void testListProjects() throws Exception {
        for (int i = 1; i <= 3; i++) {
            Project project = new Project();
            project.setId("proj-" + i);
            project.setName("Test Project " + i);
            project.setGitRepo("https://github.com/test/repo" + i);
            project.setStatus("ACTIVE");
            project.setCreatedBy("admin");
            projectRepository.insert(project);
        }

        mockMvc.perform(get("/lg/projects")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total").value(3));
    }

    @Test
    void testCreateProject_Success() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setName("New Project");
        request.setGitRepo("https://github.com/test/new-project");
        request.setDescription("Test description");

        mockMvc.perform(post("/lg/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.name").value("New Project"));
    }

    @Test
    void testCreateProject_MissingName() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setGitRepo("https://github.com/test/new-project");

        mockMvc.perform(post("/lg/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void testDeleteProject_Success() throws Exception {
        Project project = new Project();
        project.setId("proj-to-delete");
        project.setName("To Delete");
        project.setGitRepo("https://github.com/test/delete");
        project.setStatus("ACTIVE");
        project.setCreatedBy("admin");
        projectRepository.insert(project);

        mockMvc.perform(delete("/lg/projects/proj-to-delete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testDeleteProject_NotFound() throws Exception {
        mockMvc.perform(delete("/lg/projects/nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }
}
