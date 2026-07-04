package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.scan.ResolvedScanPlan;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.DbConnectionRepository;
import io.github.legacygraph.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanScopeResolverTest {

    @Mock private CodeRepoRepository codeRepoRepository;
    @Mock private DbConnectionRepository dbConnectionRepository;
    @Mock private DocumentRepository documentRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolve_emptyScopeArraysMeanNothingSelected() {
        lenient().when(codeRepoRepository.selectList(any())).thenReturn(List.of(codeRepo("repo-1")));
        lenient().when(dbConnectionRepository.selectList(any())).thenReturn(List.of(dbConnection("db-1")));
        lenient().when(documentRepository.selectList(any())).thenReturn(List.of(document("doc-1")));

        ScanScopeResolver resolver = new ScanScopeResolver(
                codeRepoRepository, dbConnectionRepository, documentRepository, objectMapper);

        ResolvedScanPlan plan = resolver.resolve("project-1", "version-1",
                "{\"repoIds\":[],\"dbIds\":[],\"docIds\":[],\"scanTypes\":[\"DOC_PARSE\"]}");

        assertTrue(plan.getRepos().isEmpty());
        assertTrue(plan.getDatabases().isEmpty());
        assertTrue(plan.getDocuments().isEmpty());
    }

    @Test
    void resolve_absentScopeArraysKeepLegacyAllSourcesDefault() {
        when(codeRepoRepository.selectList(any())).thenReturn(List.of(codeRepo("repo-1")));
        when(dbConnectionRepository.selectList(any())).thenReturn(List.of(dbConnection("db-1")));
        when(documentRepository.selectList(any())).thenReturn(List.of(document("doc-1")));
        ScanScopeResolver resolver = new ScanScopeResolver(
                codeRepoRepository, dbConnectionRepository, documentRepository, objectMapper);

        ResolvedScanPlan plan = resolver.resolve("project-1", "version-1", "{\"scanTypes\":[\"DOC_PARSE\"]}");

        assertEquals(List.of("repo-1"), plan.getRepos().stream().map(r -> r.getRepoId()).toList());
        assertEquals(List.of("db-1"), plan.getDatabases().stream().map(d -> d.getConnectionId()).toList());
        assertEquals(List.of("doc-1"), plan.getDocuments().stream().map(d -> d.getDocId()).toList());
    }

    private CodeRepo codeRepo(String id) {
        CodeRepo repo = new CodeRepo();
        repo.setId(id);
        repo.setLocalPath("/tmp/" + id);
        return repo;
    }

    private DbConnection dbConnection(String id) {
        DbConnection connection = new DbConnection();
        connection.setId(id);
        connection.setDbType("postgresql");
        return connection;
    }

    private Document document(String id) {
        Document document = new Document();
        document.setId(id);
        document.setDocName(id + ".md");
        document.setFilePath("/tmp/" + id + ".md");
        return document;
    }
}
