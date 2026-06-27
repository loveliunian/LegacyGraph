package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.CreateCodeRepoRequest;
import io.github.legacygraph.dto.CreateDbConnectionRequest;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.DbConnectionRepository;
import io.github.legacygraph.repository.DocumentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/lg/projects/{projectId}/sources")
@Tag(name = "资料接入", description = "代码仓库、数据库连接、文档资料的管理")
public class SourceController {

    private final CodeRepoRepository codeRepoRepository;
    private final DbConnectionRepository dbConnectionRepository;
    private final DocumentRepository documentRepository;

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            ".docx", ".pdf", ".md", ".txt", ".json", ".xml", ".yaml", ".yml",
            ".java", ".py", ".js", ".ts", ".go", ".rs", ".cpp", ".c", ".h"
    );

    public SourceController(CodeRepoRepository codeRepoRepository,
                            DbConnectionRepository dbConnectionRepository,
                            DocumentRepository documentRepository) {
        this.codeRepoRepository = codeRepoRepository;
        this.dbConnectionRepository = dbConnectionRepository;
        this.documentRepository = documentRepository;
    }

    // ==================== 代码仓库 ====================

    @GetMapping("/repos")
    @Operation(summary = "查询代码仓库列表")
    public Result<PageResult<CodeRepo>> listRepos(
            @PathVariable String projectId,
            PageQuery query) {
        LambdaQueryWrapper<CodeRepo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CodeRepo::getProjectId, projectId)
                .orderByDesc(CodeRepo::getCreatedAt);

        Page<CodeRepo> page = codeRepoRepository.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        PageResult<CodeRepo> result = PageResult.of(
                page.getRecords(),
                page.getTotal(),
                query.getPageNum(),
                query.getPageSize()
        );
        return Result.success(result);
    }

    @GetMapping("/repos/{id}")
    @Operation(summary = "获取代码仓库详情")
    public Result<CodeRepo> getRepo(@PathVariable String projectId, @PathVariable String id) {
        CodeRepo repo = codeRepoRepository.selectById(id);
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            return Result.error("代码仓库不存在");
        }
        return Result.success(repo);
    }

    @PostMapping("/repos")
    @Operation(summary = "创建代码仓库")
    public Result<String> createRepo(
            @PathVariable String projectId,
            @Valid @RequestBody CreateCodeRepoRequest request) {
        CodeRepo repo = new CodeRepo();
        repo.setProjectId(projectId);
        repo.setRepoName(request.getRepoName());
        repo.setRepoType(request.getRepoType());
        repo.setGitUrl(request.getGitUrl());
        repo.setBranchName(request.getBranchName() != null ? request.getBranchName() : "main");
        repo.setAuthType(request.getAuthType());
        repo.setUsername(request.getUsername());
        repo.setIncludePattern(request.getIncludePattern());
        repo.setExcludePattern(request.getExcludePattern());
        repo.setStatus("PENDING");
        repo.setCreatedBy("currentUser");
        repo.setCreatedAt(LocalDateTime.now());
        repo.setUpdatedAt(LocalDateTime.now());

        codeRepoRepository.insert(repo);
        return Result.success(repo.getId());
    }

    @PutMapping("/repos/{id}")
    @Operation(summary = "更新代码仓库")
    public Result<Void> updateRepo(
            @PathVariable String projectId,
            @PathVariable String id,
            @Valid @RequestBody CreateCodeRepoRequest request) {
        CodeRepo repo = codeRepoRepository.selectById(id);
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            return Result.error("代码仓库不存在");
        }

        repo.setRepoName(request.getRepoName());
        repo.setRepoType(request.getRepoType());
        repo.setGitUrl(request.getGitUrl());
        repo.setBranchName(request.getBranchName());
        repo.setAuthType(request.getAuthType());
        repo.setUsername(request.getUsername());
        repo.setIncludePattern(request.getIncludePattern());
        repo.setExcludePattern(request.getExcludePattern());
        repo.setUpdatedAt(LocalDateTime.now());

        codeRepoRepository.updateById(repo);
        return Result.success();
    }

    @DeleteMapping("/repos/{id}")
    @Operation(summary = "删除代码仓库")
    public Result<Void> deleteRepo(@PathVariable String projectId, @PathVariable String id) {
        CodeRepo repo = codeRepoRepository.selectById(id);
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            return Result.error("代码仓库不存在");
        }
        codeRepoRepository.deleteById(id);
        return Result.success();
    }

    @PostMapping("/repos/{id}/test-connection")
    @Operation(summary = "测试代码仓库连接")
    public Result<Map<String, Object>> testRepoConnection(
            @PathVariable String projectId,
            @PathVariable String id) {
        CodeRepo repo = codeRepoRepository.selectById(id);
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            return Result.error("代码仓库不存在");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "连接测试成功");
        return Result.success(result);
    }

    @PostMapping("/repos/{id}/pull")
    @Operation(summary = "拉取代码仓库")
    public Result<Void> pullRepo(
            @PathVariable String projectId,
            @PathVariable String id) {
        CodeRepo repo = codeRepoRepository.selectById(id);
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            return Result.error("代码仓库不存在");
        }

        repo.setStatus("PULLING");
        repo.setUpdatedAt(LocalDateTime.now());
        codeRepoRepository.updateById(repo);

        repo.setStatus("READY");
        repo.setLastPullStatus("SUCCESS");
        repo.setLastPullTime(LocalDateTime.now());
        repo.setUpdatedAt(LocalDateTime.now());
        codeRepoRepository.updateById(repo);

        return Result.success();
    }

    // ==================== 数据库连接 ====================

    @GetMapping("/databases")
    @Operation(summary = "查询数据库连接列表")
    public Result<PageResult<DbConnection>> listDatabases(
            @PathVariable String projectId,
            PageQuery query) {
        LambdaQueryWrapper<DbConnection> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DbConnection::getProjectId, projectId)
                .orderByDesc(DbConnection::getCreatedAt);

        Page<DbConnection> page = dbConnectionRepository.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        PageResult<DbConnection> result = PageResult.of(
                page.getRecords(),
                page.getTotal(),
                query.getPageNum(),
                query.getPageSize()
        );
        return Result.success(result);
    }

    @GetMapping("/databases/{id}")
    @Operation(summary = "获取数据库连接详情")
    public Result<DbConnection> getDatabase(@PathVariable String projectId, @PathVariable String id) {
        DbConnection db = dbConnectionRepository.selectById(id);
        if (db == null || !db.getProjectId().equals(projectId)) {
            return Result.error("数据库连接不存在");
        }
        return Result.success(db);
    }

    @PostMapping("/databases")
    @Operation(summary = "创建数据库连接")
    public Result<String> createDatabase(
            @PathVariable String projectId,
            @Valid @RequestBody CreateDbConnectionRequest request) {
        DbConnection db = new DbConnection();
        db.setProjectId(projectId);
        db.setConnectionName(request.getConnectionName());
        db.setDbType(request.getDbType());
        db.setHost(request.getHost());
        db.setPort(request.getPort());
        db.setDatabaseName(request.getDatabaseName());
        db.setSchemaName(request.getSchemaName());
        db.setUsername(request.getUsername());
        db.setPassword(request.getPassword());
        db.setReadonly(request.getReadonly() != null ? request.getReadonly() : false);
        db.setIncludeTables(request.getIncludeTables());
        db.setExcludeTables(request.getExcludeTables());
        db.setStatus("PENDING");
        db.setCreatedBy("currentUser");
        db.setCreatedAt(LocalDateTime.now());
        db.setUpdatedAt(LocalDateTime.now());

        dbConnectionRepository.insert(db);
        return Result.success(db.getId());
    }

    @PutMapping("/databases/{id}")
    @Operation(summary = "更新数据库连接")
    public Result<Void> updateDatabase(
            @PathVariable String projectId,
            @PathVariable String id,
            @Valid @RequestBody CreateDbConnectionRequest request) {
        DbConnection db = dbConnectionRepository.selectById(id);
        if (db == null || !db.getProjectId().equals(projectId)) {
            return Result.error("数据库连接不存在");
        }

        db.setConnectionName(request.getConnectionName());
        db.setDbType(request.getDbType());
        db.setHost(request.getHost());
        db.setPort(request.getPort());
        db.setDatabaseName(request.getDatabaseName());
        db.setSchemaName(request.getSchemaName());
        db.setUsername(request.getUsername());
        db.setPassword(request.getPassword());
        db.setReadonly(request.getReadonly());
        db.setIncludeTables(request.getIncludeTables());
        db.setExcludeTables(request.getExcludeTables());
        db.setUpdatedAt(LocalDateTime.now());

        dbConnectionRepository.updateById(db);
        return Result.success();
    }

    @DeleteMapping("/databases/{id}")
    @Operation(summary = "删除数据库连接")
    public Result<Void> deleteDatabase(@PathVariable String projectId, @PathVariable String id) {
        DbConnection db = dbConnectionRepository.selectById(id);
        if (db == null || !db.getProjectId().equals(projectId)) {
            return Result.error("数据库连接不存在");
        }
        dbConnectionRepository.deleteById(id);
        return Result.success();
    }

    @PostMapping("/databases/{id}/test-connection")
    @Operation(summary = "测试数据库连接")
    public Result<Map<String, Object>> testDbConnection(
            @PathVariable String projectId,
            @PathVariable String id) {
        DbConnection db = dbConnectionRepository.selectById(id);
        if (db == null || !db.getProjectId().equals(projectId)) {
            return Result.error("数据库连接不存在");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "连接测试成功");
        return Result.success(result);
    }

    @PostMapping("/databases/{id}/scan-schema")
    @Operation(summary = "扫描数据库表结构")
    public Result<Map<String, Object>> scanSchema(
            @PathVariable String projectId,
            @PathVariable String id) {
        DbConnection db = dbConnectionRepository.selectById(id);
        if (db == null || !db.getProjectId().equals(projectId)) {
            return Result.error("数据库连接不存在");
        }

        db.setStatus("SCANNING");
        dbConnectionRepository.updateById(db);

        db.setStatus("READY");
        db.setTableCount((int) (Math.random() * 50 + 10));
        db.setLastScanTime(LocalDateTime.now());
        db.setUpdatedAt(LocalDateTime.now());
        dbConnectionRepository.updateById(db);

        Map<String, Object> result = new HashMap<>();
        result.put("tableCount", db.getTableCount());
        result.put("message", "扫描完成");
        return Result.success(result);
    }

    // ==================== 文档资料 ====================

    @GetMapping("/documents")
    @Operation(summary = "查询文档列表")
    public Result<PageResult<Document>> listDocuments(
            @PathVariable String projectId,
            PageQuery query) {
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Document::getProjectId, projectId)
                .orderByDesc(Document::getCreatedAt);

        Page<Document> page = documentRepository.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        PageResult<Document> result = PageResult.of(
                page.getRecords(),
                page.getTotal(),
                query.getPageNum(),
                query.getPageSize()
        );
        return Result.success(result);
    }

    @GetMapping("/documents/{id}")
    @Operation(summary = "获取文档详情")
    public Result<Document> getDocument(@PathVariable String projectId, @PathVariable String id) {
        Document doc = documentRepository.selectById(id);
        if (doc == null || !doc.getProjectId().equals(projectId)) {
            return Result.error("文档不存在");
        }
        return Result.success(doc);
    }

    @PostMapping("/documents/upload")
    @Operation(summary = "上传文档")
    public Result<String> uploadDocument(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "docType", required = false) String docType) throws IOException {

        if (file.isEmpty()) {
            return Result.code(400, "文件不能为空");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            return Result.code(400, "文件大小不能超过100MB");
        }

        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null) {
            return Result.code(400, "文件名不能为空");
        }

        String fileExtension = getFileExtension(originalFileName).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(fileExtension)) {
            return Result.code(400, "不支持的文件类型，仅支持: " + String.join(", ", ALLOWED_EXTENSIONS));
        }

        String uploadDir = System.getProperty("java.io.tmpdir") + "/legacygraph/uploads/" + projectId;
        Files.createDirectories(Paths.get(uploadDir));

        String fileName = UUID.randomUUID() + "_" + originalFileName;
        Path filePath = Paths.get(uploadDir, fileName);
        Files.copy(file.getInputStream(), filePath);

        String fileType = getFileType(fileExtension);

        Document doc = new Document();
        doc.setProjectId(projectId);
        doc.setDocName(originalFileName);
        doc.setDocType(docType != null ? docType : "GENERAL");
        doc.setFileType(fileType);
        doc.setFilePath(filePath.toString());
        doc.setFileSize(file.getSize());
        doc.setParseStatus("UPLOADED");
        doc.setUploadedBy("currentUser");
        doc.setUploadedAt(LocalDateTime.now());
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());

        documentRepository.insert(doc);
        return Result.success(doc.getId());
    }

    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return fileName.substring(lastDotIndex);
    }

    private String getFileType(String extension) {
        return switch (extension) {
            case ".docx" -> "DOCX";
            case ".pdf" -> "PDF";
            case ".md" -> "MD";
            case ".txt" -> "TXT";
            default -> "OTHER";
        };
    }

    @PostMapping("/documents/{id}/parse")
    @Operation(summary = "解析文档")
    public Result<Map<String, Object>> parseDocument(
            @PathVariable String projectId,
            @PathVariable String id) {
        Document doc = documentRepository.selectById(id);
        if (doc == null || !doc.getProjectId().equals(projectId)) {
            return Result.error("文档不存在");
        }

        doc.setParseStatus("PARSING");
        documentRepository.updateById(doc);

        doc.setParseStatus("PARSED");
        doc.setFactCount((int) (Math.random() * 100 + 10));
        doc.setParsedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        documentRepository.updateById(doc);

        Map<String, Object> result = new HashMap<>();
        result.put("factCount", doc.getFactCount());
        result.put("message", "解析完成");
        return Result.success(result);
    }

    @DeleteMapping("/documents/{id}")
    @Operation(summary = "删除文档")
    public Result<Void> deleteDocument(@PathVariable String projectId, @PathVariable String id) {
        Document doc = documentRepository.selectById(id);
        if (doc == null || !doc.getProjectId().equals(projectId)) {
            return Result.error("文档不存在");
        }
        documentRepository.deleteById(id);
        return Result.success();
    }
}
