package io.github.legacygraph.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.CreateCodeRepoRequest;
import io.github.legacygraph.dto.CreateDbConnectionRequest;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.DbConnectionRepository;
import io.github.legacygraph.repository.DocumentRepository;
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
class SourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CodeRepoRepository codeRepoRepository;

    @Autowired
    private DbConnectionRepository dbConnectionRepository;

    @Autowired
    private DocumentRepository documentRepository;

    private final String testProjectId = "test-project-1";

    @BeforeEach
    void setUp() {
        codeRepoRepository.delete(null);
        dbConnectionRepository.delete(null);
        documentRepository.delete(null);
    }

    // ==================== Code Repo Tests ====================

    @Test
    void testListRepos_Empty() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/sources/repos", testProjectId)
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testCreateRepo_Success() throws Exception {
        CreateCodeRepoRequest request = new CreateCodeRepoRequest();
        request.setRepoName("test-repo");
        request.setRepoType("GIT");
        request.setGitUrl("https://github.com/test/repo.git");
        request.setBranchName("main");
        request.setAuthType("NONE");

        mockMvc.perform(post("/lg/projects/{projectId}/sources/repos", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGetRepo_Success() throws Exception {
        CodeRepo repo = new CodeRepo();
        repo.setProjectId(testProjectId);
        repo.setRepoName("test-repo");
        repo.setRepoType("GIT");
        codeRepoRepository.insert(repo);

        mockMvc.perform(get("/lg/projects/{projectId}/sources/repos/{id}", testProjectId, repo.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.repoName").value("test-repo"));
    }

    @Test
    void testGetRepo_NotFound() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/sources/repos/{id}", testProjectId, "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void testUpdateRepo_Success() throws Exception {
        CodeRepo repo = new CodeRepo();
        repo.setProjectId(testProjectId);
        repo.setRepoName("test-repo");
        repo.setRepoType("GIT");
        codeRepoRepository.insert(repo);

        CreateCodeRepoRequest request = new CreateCodeRepoRequest();
        request.setRepoName("updated-repo");
        request.setRepoType("GIT");
        request.setGitUrl("https://github.com/test/repo.git");
        request.setBranchName("main");

        mockMvc.perform(put("/lg/projects/{projectId}/sources/repos/{id}", testProjectId, repo.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testDeleteRepo_Success() throws Exception {
        CodeRepo repo = new CodeRepo();
        repo.setProjectId(testProjectId);
        repo.setRepoName("test-repo");
        codeRepoRepository.insert(repo);

        mockMvc.perform(delete("/lg/projects/{projectId}/sources/repos/{id}", testProjectId, repo.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testTestRepoConnection_Success() throws Exception {
        CodeRepo repo = new CodeRepo();
        repo.setProjectId(testProjectId);
        repo.setRepoName("test-repo");
        codeRepoRepository.insert(repo);

        mockMvc.perform(post("/lg/projects/{projectId}/sources/repos/{id}/test-connection", testProjectId, repo.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));
    }

    @Test
    void testPullRepo_Success() throws Exception {
        CodeRepo repo = new CodeRepo();
        repo.setProjectId(testProjectId);
        repo.setRepoName("test-repo");
        codeRepoRepository.insert(repo);

        mockMvc.perform(post("/lg/projects/{projectId}/sources/repos/{id}/pull", testProjectId, repo.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== Database Connection Tests ====================

    @Test
    void testListDatabases_Empty() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/sources/databases", testProjectId)
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testCreateDatabase_Success() throws Exception {
        CreateDbConnectionRequest request = new CreateDbConnectionRequest();
        request.setConnectionName("test-db");
        request.setDbType("POSTGRESQL");
        request.setHost("localhost");
        request.setPort(5432);
        request.setDatabaseName("testdb");
        request.setUsername("postgres");
        request.setPassword("password");

        mockMvc.perform(post("/lg/projects/{projectId}/sources/databases", testProjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGetDatabase_Success() throws Exception {
        DbConnection db = new DbConnection();
        db.setProjectId(testProjectId);
        db.setConnectionName("test-db");
        db.setDbType("POSTGRESQL");
        dbConnectionRepository.insert(db);

        mockMvc.perform(get("/lg/projects/{projectId}/sources/databases/{id}", testProjectId, db.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.connectionName").value("test-db"));
    }

    @Test
    void testUpdateDatabase_Success() throws Exception {
        DbConnection db = new DbConnection();
        db.setProjectId(testProjectId);
        db.setConnectionName("test-db");
        db.setDbType("POSTGRESQL");
        dbConnectionRepository.insert(db);

        CreateDbConnectionRequest request = new CreateDbConnectionRequest();
        request.setConnectionName("updated-db");
        request.setDbType("POSTGRESQL");
        request.setHost("localhost");
        request.setPort(5432);
        request.setDatabaseName("testdb");
        request.setUsername("postgres");
        request.setPassword("password");

        mockMvc.perform(put("/lg/projects/{projectId}/sources/databases/{id}", testProjectId, db.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testDeleteDatabase_Success() throws Exception {
        DbConnection db = new DbConnection();
        db.setProjectId(testProjectId);
        db.setConnectionName("test-db");
        dbConnectionRepository.insert(db);

        mockMvc.perform(delete("/lg/projects/{projectId}/sources/databases/{id}", testProjectId, db.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testTestDbConnection_Success() throws Exception {
        DbConnection db = new DbConnection();
        db.setProjectId(testProjectId);
        db.setConnectionName("test-db");
        dbConnectionRepository.insert(db);

        mockMvc.perform(post("/lg/projects/{projectId}/sources/databases/{id}/test-connection", testProjectId, db.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));
    }

    @Test
    void testScanSchema_Success() throws Exception {
        DbConnection db = new DbConnection();
        db.setProjectId(testProjectId);
        db.setConnectionName("test-db");
        dbConnectionRepository.insert(db);

        mockMvc.perform(post("/lg/projects/{projectId}/sources/databases/{id}/scan-schema", testProjectId, db.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tableCount").exists());
    }

    // ==================== Document Tests ====================

    @Test
    void testListDocuments_Empty() throws Exception {
        mockMvc.perform(get("/lg/projects/{projectId}/sources/documents", testProjectId)
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void testGetDocument_Success() throws Exception {
        Document doc = new Document();
        doc.setProjectId(testProjectId);
        doc.setDocName("test-doc.md");
        doc.setDocType("GENERAL");
        documentRepository.insert(doc);

        mockMvc.perform(get("/lg/projects/{projectId}/sources/documents/{id}", testProjectId, doc.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.docName").value("test-doc.md"));
    }

    @Test
    void testParseDocument_Success() throws Exception {
        Document doc = new Document();
        doc.setProjectId(testProjectId);
        doc.setDocName("test-doc.md");
        doc.setParseStatus("UPLOADED");
        documentRepository.insert(doc);

        mockMvc.perform(post("/lg/projects/{projectId}/sources/documents/{id}/parse", testProjectId, doc.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.factCount").exists());
    }

    @Test
    void testDeleteDocument_Success() throws Exception {
        Document doc = new Document();
        doc.setProjectId(testProjectId);
        doc.setDocName("test-doc.md");
        documentRepository.insert(doc);

        mockMvc.perform(delete("/lg/projects/{projectId}/sources/documents/{id}", testProjectId, doc.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
