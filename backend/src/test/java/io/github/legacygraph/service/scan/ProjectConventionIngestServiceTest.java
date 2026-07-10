package io.github.legacygraph.service.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.ProjectRepository;
import io.github.legacygraph.service.qa.VectorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ProjectConventionIngestService} 单元测试。
 * 验证：pom.xml/package.json 解析、techStack 优先级、向量化调用、不可用时跳过。
 */
@ExtendWith(MockitoExtension.class)
class ProjectConventionIngestServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private VectorizationService vectorizationService;
    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private CodeRepoRepository codeRepoRepository;

    private ProjectConventionIngestService service;

    @BeforeEach
    void setUp() {
        service = new ProjectConventionIngestService(
                projectRepository, vectorizationService, neo4jGraphDao, codeRepoRepository,
                new ObjectMapper());
    }

    @Test
    void parsePomXml_extractsSpringBootAndMybatis(@TempDir Path tempDir) throws Exception {
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.2.0</version>
                    </parent>
                    <properties>
                        <mybatis.version>3.5.13</mybatis.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>com.baomidou</groupId>
                            <artifactId>mybatis-plus-boot-starter</artifactId>
                            <version>3.5.5</version>
                        </dependency>
                        <dependency>
                            <groupId>mysql</groupId>
                            <artifactId>mysql-connector-java</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);

        String result = service.parsePomXml(pomPath);

        assertNotNull(result);
        assertTrue(result.contains("Spring Boot"), "应提取 Spring Boot 框架");
        assertTrue(result.contains("3.2.0"), "应提取 Spring Boot 版本");
        assertTrue(result.contains("MyBatis"), "应提取 MyBatis ORM");
        assertTrue(result.contains("MySQL"), "应提取 MySQL 数据库");
        assertTrue(result.contains("Spring Web"), "应提取 Spring Web");
    }

    @Test
    void parsePackageJson_extractsVueAndVite(@TempDir Path tempDir) throws Exception {
        String pkgContent = """
                {
                  "dependencies": {
                    "vue": "^3.4.0",
                    "element-plus": "^2.5.0",
                    "axios": "^1.6.0",
                    "pinia": "^2.1.0"
                  },
                  "devDependencies": {
                    "vite": "^5.0.0",
                    "vue-router": "^4.2.0"
                  }
                }
                """;
        Path pkgPath = tempDir.resolve("package.json");
        Files.writeString(pkgPath, pkgContent);

        String result = service.parsePackageJson(pkgPath);

        assertNotNull(result);
        assertTrue(result.contains("Vue"), "应提取 Vue 框架");
        assertTrue(result.contains("Element"), "应提取 Element UI 组件库");
        assertTrue(result.contains("Vite"), "应提取 Vite 构建工具");
        assertTrue(result.contains("Axios"), "应提取 Axios HTTP 客户端");
        assertTrue(result.contains("Pinia"), "应提取 Pinia 状态管理");
    }

    @Test
    void ingestProjectConvention_usesTechStackWhenPresent() {
        Project project = new Project();
        project.setTechStack("{\"framework\":\"Spring Boot\",\"orm\":\"MyBatis-Plus\"}");
        when(vectorizationService.isAvailable()).thenReturn(true);
        when(projectRepository.selectById("p1")).thenReturn(project);

        service.ingestProjectConvention("p1", "v1", List.of());

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(vectorizationService).deleteBySourceUri(eq(ProjectConventionIngestService.SOURCE_URI));
        verify(vectorizationService).embedDocument(
                eq("p1"), any(), eq(ProjectConventionIngestService.CHUNK_TYPE),
                eq(ProjectConventionIngestService.SOURCE_URI), contentCaptor.capture(),
                anyInt(), anyInt(), anyString());
        String markdown = contentCaptor.getValue();
        assertTrue(markdown.contains("Spring Boot"), "向量化内容应包含 techStack 中的框架信息");
        assertTrue(markdown.contains("## 技术栈"), "应包含技术栈章节");
        assertTrue(markdown.contains("## 分层规范"), "应包含分层规范章节");
        assertTrue(markdown.contains("## 命名约定"), "应包含命名约定章节");
    }

    @Test
    void ingestProjectConvention_fallsBackToPomXmlWhenTechStackEmpty(@TempDir Path tempDir) throws Exception {
        // 准备 pom.xml
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>2.7.0</version>
                    </parent>
                    <dependencies>
                        <dependency>
                            <artifactId>mybatis-plus-boot-starter</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pomContent);

        CodeRepo repo = new CodeRepo();
        repo.setRepoName("test-repo");
        repo.setLocalPath(tempDir.toString());

        when(vectorizationService.isAvailable()).thenReturn(true);
        when(projectRepository.selectById("p2")).thenReturn(null); // techStack 为空

        service.ingestProjectConvention("p2", "v2", List.of(repo));

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(vectorizationService).embedDocument(
                eq("p2"), any(), anyString(), anyString(), contentCaptor.capture(),
                anyInt(), anyInt(), anyString());
        String markdown = contentCaptor.getValue();
        assertTrue(markdown.contains("Spring Boot"), "techStack 为空时应从 pom.xml 解析 Spring Boot");
        assertTrue(markdown.contains("MyBatis"), "techStack 为空时应从 pom.xml 解析 MyBatis");
    }

    @Test
    void ingestProjectConvention_skipsWhenVectorizationUnavailable() {
        when(vectorizationService.isAvailable()).thenReturn(false);

        service.ingestProjectConvention("p3", "v3", List.of());

        verify(vectorizationService, never()).embedDocument(anyString(), any(), anyString(), anyString(),
                anyString(), anyInt(), anyInt(), anyString());
        verify(vectorizationService, never()).deleteBySourceUri(anyString());
    }

    @Test
    void ingest_delegatesToIngestProjectConvention() {
        when(vectorizationService.isAvailable()).thenReturn(false);
        when(codeRepoRepository.selectList(any())).thenReturn(List.of());

        service.ingest("p4", "v4");

        // ingest 内部查询 repos 后调用 ingestProjectConvention，后者检查 isAvailable 后跳过
        verify(codeRepoRepository).selectList(any());
    }

    @Test
    void sourceUri_matchesConvention() {
        // sourceUri 应为 docs/legacygraph/project-convention.md，便于溯源
        assertEquals("docs/legacygraph/project-convention.md", ProjectConventionIngestService.SOURCE_URI);
    }
}
