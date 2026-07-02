package io.github.legacygraph.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.scan.ResolvedDbScope;
import io.github.legacygraph.dto.scan.ResolvedDocScope;
import io.github.legacygraph.dto.scan.ResolvedRepoScope;
import io.github.legacygraph.dto.scan.ResolvedScanPlan;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.DbConnectionRepository;
import io.github.legacygraph.repository.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.*;

/**
 * 扫描范围解析器。
 * 将 scanScope JSON 解析为强类型的 {@link ResolvedScanPlan}，
 * 从 ProjectScanner 中分离 scope 解析、路径解析和发现准备逻辑。
 */
@Slf4j
@Component
public class ScanScopeResolver {

    private final CodeRepoRepository codeRepoRepository;
    private final DbConnectionRepository dbConnectionRepository;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    private static final int DEFAULT_MAX_FILES = 500;
    private static final int DEFAULT_MAX_DOCS = 50;
    private static final int DEFAULT_MAX_DB_TABLES = 200;

    public ScanScopeResolver(CodeRepoRepository codeRepoRepository,
                             DbConnectionRepository dbConnectionRepository,
                             DocumentRepository documentRepository,
                             ObjectMapper objectMapper) {
        this.codeRepoRepository = codeRepoRepository;
        this.dbConnectionRepository = dbConnectionRepository;
        this.documentRepository = documentRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 从 scanScope JSON 和项目信息解析扫描计划。
     *
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @param scanScope scanScope JSON（可为空）
     * @return 解析后的扫描计划
     */
    public ResolvedScanPlan resolve(String projectId, String versionId, String scanScope) {
        // 解析 scanScope 中的过滤条件
        List<String> scopeRepoIds = null;
        List<String> scopeDbIds = null;
        List<String> scopeDocIds = null;
        List<String> scopeScanTypes = null;
        boolean aiEnabled = false;
        boolean incremental = false;
        int maxFiles = DEFAULT_MAX_FILES;
        int maxDocs = DEFAULT_MAX_DOCS;
        int maxDbTables = DEFAULT_MAX_DB_TABLES;

        if (scanScope != null && !scanScope.isBlank()) {
            try {
                JsonNode scopeNode = objectMapper.readTree(scanScope);
                if (scopeNode.has("repoIds") && scopeNode.get("repoIds").isArray()) {
                    scopeRepoIds = new ArrayList<>();
                    for (JsonNode n : scopeNode.get("repoIds")) scopeRepoIds.add(n.asText());
                }
                if (scopeNode.has("dbIds") && scopeNode.get("dbIds").isArray()) {
                    scopeDbIds = new ArrayList<>();
                    for (JsonNode n : scopeNode.get("dbIds")) scopeDbIds.add(n.asText());
                }
                if (scopeNode.has("docIds") && scopeNode.get("docIds").isArray()) {
                    scopeDocIds = new ArrayList<>();
                    for (JsonNode n : scopeNode.get("docIds")) scopeDocIds.add(n.asText());
                }
                if (scopeNode.has("scanTypes") && scopeNode.get("scanTypes").isArray()) {
                    scopeScanTypes = new ArrayList<>();
                    for (JsonNode n : scopeNode.get("scanTypes")) scopeScanTypes.add(n.asText());
                }
                if (scopeNode.has("aiEnabled")) {
                    aiEnabled = scopeNode.get("aiEnabled").asBoolean();
                }
                if (scopeNode.has("incremental")) {
                    incremental = scopeNode.get("incremental").asBoolean();
                }
                if (scopeNode.has("maxFiles")) {
                    maxFiles = scopeNode.get("maxFiles").asInt();
                }
                if (scopeNode.has("maxDocs")) {
                    maxDocs = scopeNode.get("maxDocs").asInt();
                }
                if (scopeNode.has("maxDbTables")) {
                    maxDbTables = scopeNode.get("maxDbTables").asInt();
                }
            } catch (Exception e) {
                log.debug("Failed to parse scanScope, using defaults: {}", e.getMessage());
            }
        }

        // 解析仓库范围
        List<ResolvedRepoScope> repos = resolveRepos(projectId, scopeRepoIds);

        // 解析数据库范围
        List<ResolvedDbScope> databases = resolveDatabases(projectId, scopeDbIds);

        // 解析文档范围
        List<ResolvedDocScope> documents = resolveDocuments(projectId, scopeDocIds);

        return ResolvedScanPlan.builder()
                .projectId(projectId)
                .versionId(versionId)
                .repos(repos)
                .databases(databases)
                .documents(documents)
                .scanTypes(scopeScanTypes != null ? new HashSet<>(scopeScanTypes) : new HashSet<>())
                .aiEnabled(aiEnabled)
                .incremental(incremental)
                .maxFiles(maxFiles)
                .maxDocs(maxDocs)
                .maxDbTables(maxDbTables)
                .rawScope(parseRawScope(scanScope))
                .build();
    }

    /**
     * 解析仓库范围，补全本地路径信息。
     */
    private List<ResolvedRepoScope> resolveRepos(String projectId, List<String> scopeRepoIds) {
        LambdaQueryWrapper<CodeRepo> repoQuery = new LambdaQueryWrapper<CodeRepo>()
                .eq(CodeRepo::getProjectId, projectId);
        if (scopeRepoIds != null && !scopeRepoIds.isEmpty()) {
            repoQuery.in(CodeRepo::getId, scopeRepoIds);
        }
        List<CodeRepo> repos = codeRepoRepository.selectList(repoQuery);

        List<ResolvedRepoScope> result = new ArrayList<>();
        for (CodeRepo repo : repos) {
            String baseDir = repo.getLocalPath();
            if (baseDir == null || baseDir.isBlank()) {
                baseDir = System.getProperty("user.home") + "/.legacygraph/repos/" + projectId + "/" + repo.getId();
            }

            String backendDir = repo.getBackendSubPath() != null && !repo.getBackendSubPath().isBlank()
                    ? Paths.get(baseDir, repo.getBackendSubPath()).toString() : baseDir;
            String frontendDir = repo.getFrontendSubPath() != null && !repo.getFrontendSubPath().isBlank()
                    ? Paths.get(baseDir, repo.getFrontendSubPath()).toString() : baseDir;

            result.add(ResolvedRepoScope.builder()
                    .repoId(repo.getId())
                    .baseDir(baseDir)
                    .backendDir(backendDir)
                    .frontendDir(frontendDir)
                    .includePatterns(repo.getIncludePattern() != null
                            ? Arrays.asList(repo.getIncludePattern().split(",")) : new ArrayList<>())
                    .excludePatterns(repo.getExcludePattern() != null
                            ? Arrays.asList(repo.getExcludePattern().split(",")) : new ArrayList<>())
                    .build());
        }
        return result;
    }

    /**
     * 解析数据库范围（仅 READY 状态的连接）。
     */
    private List<ResolvedDbScope> resolveDatabases(String projectId, List<String> scopeDbIds) {
        LambdaQueryWrapper<DbConnection> dbQuery = new LambdaQueryWrapper<DbConnection>()
                .eq(DbConnection::getProjectId, projectId)
                .eq(DbConnection::getStatus, "READY");
        if (scopeDbIds != null && !scopeDbIds.isEmpty()) {
            dbQuery.in(DbConnection::getId, scopeDbIds);
        }
        List<DbConnection> connections = dbConnectionRepository.selectList(dbQuery);

        List<ResolvedDbScope> result = new ArrayList<>();
        for (DbConnection conn : connections) {
            result.add(ResolvedDbScope.builder()
                    .connectionId(conn.getId())
                    .dbType(conn.getDbType())
                    .schemaName(conn.getSchemaName())
                    .includeTables(conn.getIncludeTables() != null
                            ? Arrays.asList(conn.getIncludeTables().split(",")) : new ArrayList<>())
                    .excludeTables(conn.getExcludeTables() != null
                            ? Arrays.asList(conn.getExcludeTables().split(",")) : new ArrayList<>())
                    .build());
        }
        return result;
    }

    /**
     * 解析文档范围。
     */
    private List<ResolvedDocScope> resolveDocuments(String projectId, List<String> scopeDocIds) {
        LambdaQueryWrapper<Document> docQuery = new LambdaQueryWrapper<Document>()
                .eq(Document::getProjectId, projectId);
        if (scopeDocIds != null && !scopeDocIds.isEmpty()) {
            docQuery.in(Document::getId, scopeDocIds);
        }
        List<Document> docs = documentRepository.selectList(docQuery);

        List<ResolvedDocScope> result = new ArrayList<>();
        for (Document doc : docs) {
            result.add(ResolvedDocScope.builder()
                    .docId(doc.getId())
                    .docName(doc.getDocName())
                    .filePath(doc.getFilePath())
                    .build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRawScope(String scanScope) {
        if (scanScope == null || scanScope.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(scanScope, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
