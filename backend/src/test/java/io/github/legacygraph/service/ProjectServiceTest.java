package io.github.legacygraph.service;

import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.dto.CreateProjectRequest;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.exception.BusinessException;
import io.github.legacygraph.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private ProjectService projectService;

    private CreateProjectRequest createRequest;

    @BeforeEach
    void setUp() {
        createRequest = new CreateProjectRequest();
        createRequest.setProjectCode("test-project");
        createRequest.setProjectName("测试项目");
        createRequest.setDescription("这是一个测试项目");
        createRequest.setOwner("test-user");
    }

    @Test
    void testListProjects_Empty() {
        Page<Project> mockPage = new Page<>(1, 10);
        mockPage.setRecords(Collections.emptyList());
        mockPage.setTotal(0);

        when(projectRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<Project> result = projectService.listProjects(new PageQuery(1, 10));

        assertNotNull(result);
        assertEquals(0, result.getTotal());
        assertTrue(result.getList().isEmpty());
    }

    @Test
    void testListProjects_WithData() {
        Project project = new Project();
        project.setId("project-1");
        project.setProjectName("Test Project");

        List<Project> records = List.of(project);
        Page<Project> mockPage = new Page<>(1, 10);
        mockPage.setRecords(records);
        mockPage.setTotal(1);

        when(projectRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(mockPage);

        PageResult<Project> result = projectService.listProjects(new PageQuery(1, 10));

        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals("Test Project", result.getList().get(0).getProjectName());
    }

    @Test
    void testGetById_Found() {
        Project project = new Project();
        project.setId("project-1");
        project.setProjectName("Test Project");

        when(projectRepository.selectById("project-1")).thenReturn(project);

        Project result = projectService.getById("project-1");

        assertNotNull(result);
        assertEquals("project-1", result.getId());
        assertEquals("Test Project", result.getProjectName());
    }

    @Test
    void testGetById_NotFound() {
        when(projectRepository.selectById("nonexistent")).thenReturn(null);

        Project result = projectService.getById("nonexistent");

        assertNull(result);
    }

    @Test
    void testCreateProject_Success() {
        Project result = projectService.createProject(createRequest);

        assertNotNull(result);
        assertNotNull(result.getId()); // UUID should be generated
        assertEquals("test-project", result.getProjectCode());
        assertEquals("测试项目", result.getProjectName());
        assertEquals("这是一个测试项目", result.getDescription());
        assertEquals("test-user", result.getOwner());
        assertEquals("LEGACY", result.getProjectType()); // default
        assertEquals("INIT", result.getStatus());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());

        verify(projectRepository, times(1)).insert(any(Project.class));
    }

    @Test
    void testCreateProject_CustomType() {
        createRequest.setProjectType("MODERN");

        Project result = projectService.createProject(createRequest);

        assertNotNull(result);
        assertEquals("MODERN", result.getProjectType());
    }

    @Test
    void testDeleteById_Success() {
        Project project = new Project();
        project.setId("project-1");

        when(projectRepository.selectById("project-1")).thenReturn(project);
        doNothing().when(projectRepository).deleteById("project-1");

        assertDoesNotThrow(() -> projectService.deleteById("project-1"));

        verify(projectRepository, times(1)).deleteById("project-1");
    }

    @Test
    void testDeleteById_NotFound() {
        when(projectRepository.selectById("nonexistent")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> projectService.deleteById("nonexistent"));

        assertEquals(404, exception.getCode());
        assertEquals("项目不存在", exception.getMessage());
    }
}
