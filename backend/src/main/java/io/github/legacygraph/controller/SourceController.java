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
import io.github.legacygraph.extractors.DocumentExtractor;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.DbConnectionRepository;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.DocChunkRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
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

/**
 * 资料接入控制器
 * 管理项目的三类数据源：代码仓库、数据库连接、文档资料
 * 这些资料将被用于知识图谱抽取和分析
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/sources")
@Tag(name = "资料接入", description = "代码仓库、数据库连接、文档资料的接入与管理")
public class SourceController {

    private final CodeRepoRepository codeRepoRepository;
    private final DbConnectionRepository dbConnectionRepository;
    private final DocumentRepository documentRepository;
    private final DocChunkRepository docChunkRepository;

    /** 最大文件大小限制：100MB */
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024;

    /** 允许上传的文件扩展名列表 */
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            ".docx", ".pdf", ".md", ".txt", ".json", ".xml", ".yaml", ".yml",
            ".java", ".py", ".js", ".ts", ".go", ".rs", ".cpp", ".c", ".h"
    );

    /**
     * 构造函数注入
     * @param codeRepoRepository 代码仓库数据访问层
     * @param dbConnectionRepository 数据库连接数据访问层
     * @param documentRepository 文档数据访问层
     * @param docChunkRepository 文档片段数据访问层
     */
    public SourceController(CodeRepoRepository codeRepoRepository,
                            DbConnectionRepository dbConnectionRepository,
                            DocumentRepository documentRepository,
                            DocChunkRepository docChunkRepository) {
        this.codeRepoRepository = codeRepoRepository;
        this.dbConnectionRepository = dbConnectionRepository;
        this.documentRepository = documentRepository;
        this.docChunkRepository = docChunkRepository;
    }

    // ==================== 代码仓库 ====================

    /**
     * 分页查询代码仓库列表
     * @param projectId 项目ID
     * @param query 分页查询参数
     * @return 分页后的代码仓库列表，按创建时间倒序排列
     */
    @GetMapping("/repos")
    @Operation(summary = "分页查询代码仓库列表", description = "查询指定项目下的所有代码仓库，支持分页")
    public Result<PageResult<CodeRepo>> listRepos(
            @Parameter(description = "项目ID", required = true)
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

    /**
     * 获取代码仓库详情
     * @param projectId 项目ID
     * @param id 代码仓库ID
     * @return 代码仓库详情
     */
    @GetMapping("/repos/{id}")
    @Operation(summary = "获取代码仓库详情", description = "根据ID获取代码仓库的详细配置信息")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "404", description = "代码仓库不存在")
    })
    public Result<CodeRepo> getRepo(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "代码仓库ID", required = true)
            @PathVariable String id) {
        CodeRepo repo = codeRepoRepository.selectById(id);
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            return Result.error("代码仓库不存在");
        }
        return Result.success(repo);
    }

    /**
     * 创建代码仓库
     * 在指定项目下添加一个新的代码仓库配置
     * @param projectId 项目ID
     * @param request 创建代码仓库请求参数
     * @return 新创建的代码仓库ID
     */
    @PostMapping("/repos")
    @Operation(summary = "创建代码仓库", description = "在指定项目下创建一个新的代码仓库配置")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "创建成功，返回代码仓库ID"),
            @ApiResponse(responseCode = "400", description = "参数验证失败")
    })
    public Result<String> createRepo(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "创建请求参数", required = true)
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

    /**
     * 更新代码仓库配置
     * @param projectId 项目ID
     * @param id 代码仓库ID
     * @param request 更新请求参数
     * @return 成功结果
     */
    @PutMapping("/repos/{id}")
    @Operation(summary = "更新代码仓库配置", description = "更新指定代码仓库的配置信息")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "404", description = "代码仓库不存在")
    })
    public Result<Void> updateRepo(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "代码仓库ID", required = true)
            @PathVariable String id,
            @Parameter(description = "更新请求参数", required = true)
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

    /**
     * 删除代码仓库
     * @param projectId 项目ID
     * @param id 代码仓库ID
     * @return 成功结果
     */
    @DeleteMapping("/repos/{id}")
    @Operation(summary = "删除代码仓库", description = "删除指定的代码仓库配置")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "代码仓库不存在")
    })
    public Result<Void> deleteRepo(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "代码仓库ID", required = true)
            @PathVariable String id) {
        CodeRepo repo = codeRepoRepository.selectById(id);
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            return Result.error("代码仓库不存在");
        }
        codeRepoRepository.deleteById(id);
        return Result.success();
    }

    /**
     * 测试代码仓库连接
     * 测试能否正常连接到远程Git仓库
     * @param projectId 项目ID
     * @param id 代码仓库ID
     * @return 测试结果，包含成功标志和消息
     */
    @PostMapping("/repos/{id}/test-connection")
    @Operation(summary = "测试代码仓库连接", description = "测试能否正常连接到远程Git仓库")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "测试完成，返回测试结果"),
            @ApiResponse(responseCode = "404", description = "代码仓库不存在")
    })
    public Result<Map<String, Object>> testRepoConnection(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "代码仓库ID", required = true)
            @PathVariable String id) {
        CodeRepo repo = codeRepoRepository.selectById(id);
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            return Result.error("代码仓库不存在");
        }

        Map<String, Object> result = new HashMap<>();
        ProcessBuilder pb = new ProcessBuilder("git", "ls-remote", "--exit-code", repo.getGitUrl());
        if (repo.getUsername() != null && !repo.getUsername().isEmpty()) {
            // 认证信息由git credential helper处理，这里简化
        }
        try {
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                result.put("success", true);
                result.put("message", "连接测试成功");
                log.info("Git repository connection test succeeded: {}", repo.getGitUrl());
            } else {
                result.put("success", false);
                result.put("message", "连接测试失败，exit code: " + exitCode);
                log.warn("Git repository connection test failed with exit code: {}", exitCode);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接测试失败: " + e.getMessage());
            log.error("Git repository connection test failed", e);
        }
        return Result.success(result);
    }

    /**
     * 拉取代码仓库
     * 从远程Git仓库拉取最新代码到本地
     * @param projectId 项目ID
     * @param id 代码仓库ID
     * @return 成功结果
     */
    @PostMapping("/repos/{id}/pull")
    @Operation(summary = "拉取代码仓库", description = "从远程Git仓库拉取最新代码到本地，用于后续代码分析")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "拉取完成"),
            @ApiResponse(responseCode = "404", description = "代码仓库不存在")
    })
    public Result<Void> pullRepo(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "代码仓库ID", required = true)
            @PathVariable String id) {
        CodeRepo repo = codeRepoRepository.selectById(id);
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            return Result.error("代码仓库不存在");
        }

        repo.setStatus("PULLING");
        repo.setUpdatedAt(LocalDateTime.now());
        codeRepoRepository.updateById(repo);

        try {
            String baseDir = System.getProperty("user.home") + "/.legacygraph/repos/" + projectId;
            Files.createDirectories(Path.of(baseDir));
            String localPath = baseDir + "/" + id;
            repo.setLocalPath(localPath);

            ProcessBuilder pb;
            if (Files.exists(Path.of(localPath, ".git"))) {
                // Already cloned, do pull
                pb = new ProcessBuilder("git", "pull")
                        .directory(new java.io.File(localPath));
            } else {
                // Fresh clone
                pb = new ProcessBuilder("git", "clone", "-b", repo.getBranchName(), repo.getGitUrl(), localPath);
            }

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                repo.setStatus("READY");
                repo.setLastPullStatus("SUCCESS");
                repo.setLastPullTime(LocalDateTime.now());
                log.info("Git repository pull succeeded: {}", repo.getGitUrl());
            } else {
                repo.setStatus("PULL_FAILED");
                repo.setLastPullStatus("FAILED");
                repo.setLastPullTime(LocalDateTime.now());
                log.warn("Git repository pull failed with exit code: {}", exitCode);
            }
        } catch (Exception e) {
            repo.setStatus("PULL_FAILED");
            repo.setLastPullStatus("FAILED");
            repo.setLastPullTime(LocalDateTime.now());
            log.error("Git repository pull failed", e);
        }

        repo.setUpdatedAt(LocalDateTime.now());
        codeRepoRepository.updateById(repo);

        return Result.success();
    }

    // ==================== 数据库连接 ====================

    /**
     * 分页查询数据库连接列表
     * @param projectId 项目ID
     * @param query 分页查询参数
     * @return 分页后的数据库连接列表，按创建时间倒序排列
     */
    @GetMapping("/databases")
    @Operation(summary = "分页查询数据库连接列表", description = "查询指定项目下的所有数据库连接，支持分页")
    public Result<PageResult<DbConnection>> listDatabases(
            @Parameter(description = "项目ID", required = true)
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

    /**
     * 获取数据库连接详情
     * @param projectId 项目ID
     * @param id 数据库连接ID
     * @return 数据库连接详情
     */
    @GetMapping("/databases/{id}")
    @Operation(summary = "获取数据库连接详情", description = "根据ID获取数据库连接的详细配置信息")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "404", description = "数据库连接不存在")
    })
    public Result<DbConnection> getDatabase(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "数据库连接ID", required = true)
            @PathVariable String id) {
        DbConnection db = dbConnectionRepository.selectById(id);
        if (db == null || !db.getProjectId().equals(projectId)) {
            return Result.error("数据库连接不存在");
        }
        return Result.success(db);
    }

    /**
     * 创建数据库连接
     * 在指定项目下创建一个新的数据库连接配置
     * @param projectId 项目ID
     * @param request 创建数据库连接请求参数
     * @return 新创建的数据库连接ID
     */
    @PostMapping("/databases")
    @Operation(summary = "创建数据库连接", description = "在指定项目下创建一个新的数据库连接配置，用于后续数据库结构分析")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "创建成功，返回数据库连接ID"),
            @ApiResponse(responseCode = "400", description = "参数验证失败")
    })
    public Result<String> createDatabase(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "创建请求参数", required = true)
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

    /**
     * 更新数据库连接配置
     * @param projectId 项目ID
     * @param id 数据库连接ID
     * @param request 更新请求参数
     * @return 成功结果
     */
    @PutMapping("/databases/{id}")
    @Operation(summary = "更新数据库连接配置", description = "更新指定数据库连接的配置信息")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "404", description = "数据库连接不存在")
    })
    public Result<Void> updateDatabase(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "数据库连接ID", required = true)
            @PathVariable String id,
            @Parameter(description = "更新请求参数", required = true)
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

    /**
     * 删除数据库连接
     * @param projectId 项目ID
     * @param id 数据库连接ID
     * @return 成功结果
     */
    @DeleteMapping("/databases/{id}")
    @Operation(summary = "删除数据库连接", description = "删除指定的数据库连接配置")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "数据库连接不存在")
    })
    public Result<Void> deleteDatabase(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "数据库连接ID", required = true)
            @PathVariable String id) {
        DbConnection db = dbConnectionRepository.selectById(id);
        if (db == null || !db.getProjectId().equals(projectId)) {
            return Result.error("数据库连接不存在");
        }
        dbConnectionRepository.deleteById(id);
        return Result.success();
    }

    /**
     * 测试数据库连接
     * 测试能否正常连接到目标数据库
     * @param projectId 项目ID
     * @param id 数据库连接ID
     * @return 测试结果，包含成功标志和消息
     */
    @PostMapping("/databases/{id}/test-connection")
    @Operation(summary = "测试数据库连接", description = "测试能否正常连接到目标数据库")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "测试完成，返回测试结果"),
            @ApiResponse(responseCode = "404", description = "数据库连接不存在")
    })
    public Result<Map<String, Object>> testDbConnection(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "数据库连接ID", required = true)
            @PathVariable String id) {
        DbConnection db = dbConnectionRepository.selectById(id);
        if (db == null || !db.getProjectId().equals(projectId)) {
            return Result.error("数据库连接不存在");
        }

        Map<String, Object> result = new HashMap<>();
        try {
            // 尝试建立真实连接
            String url = buildJdbcUrl(db);
            java.sql.DriverManager.getConnection(url, db.getUsername(), db.getPassword()).close();
            result.put("success", true);
            result.put("message", "连接测试成功");
            log.info("Database connection test succeeded: {}", db.getConnectionName());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
            log.warn("Database connection test failed: {}", e.getMessage());
        }
        return Result.success(result);
    }

    /**
     * 扫描数据库表结构
     * 连接数据库并扫描所有表结构信息
     * @param projectId 项目ID
     * @param id 数据库连接ID
     * @return 扫描结果，包含表数量和消息
     */
    @PostMapping("/databases/{id}/scan-schema")
    @Operation(summary = "扫描数据库表结构", description = "连接数据库并扫描所有表结构，用于后续知识图谱抽取")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "扫描完成，返回表数量"),
            @ApiResponse(responseCode = "404", description = "数据库连接不存在")
    })
    public Result<Map<String, Object>> scanSchema(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "数据库连接ID", required = true)
            @PathVariable String id) {
        DbConnection db = dbConnectionRepository.selectById(id);
        if (db == null || !db.getProjectId().equals(projectId)) {
            return Result.error("数据库连接不存在");
        }

        db.setStatus("SCANNING");
        dbConnectionRepository.updateById(db);

        Map<String, Object> result = new HashMap<>();
        try {
            String url = buildJdbcUrl(db);
            int tableCount = 0;

            // 连接并扫描表信息
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url, db.getUsername(), db.getPassword())) {
                java.sql.DatabaseMetaData metaData = conn.getMetaData();
                String[] types = {"TABLE"};
                java.sql.ResultSet rs = metaData.getTables(null, db.getSchemaName(), "%", types);
                while (rs.next()) {
                    tableCount++;
                }
                rs.close();
            }

            db.setStatus("READY");
            db.setTableCount(tableCount);
            db.setLastScanTime(LocalDateTime.now());
            db.setUpdatedAt(LocalDateTime.now());
            dbConnectionRepository.updateById(db);

            result.put("tableCount", tableCount);
            result.put("message", "扫描完成，共发现 " + tableCount + " 张表");
            log.info("Database schema scan completed: {}, found {} tables", db.getConnectionName(), tableCount);
        } catch (Exception e) {
            db.setStatus("SCAN_FAILED");
            dbConnectionRepository.updateById(db);
            result.put("success", false);
            result.put("message", "扫描失败: " + e.getMessage());
            log.error("Database schema scan failed", e);
        }
        return Result.success(result);
    }

    /**
     * 构建JDBC URL
     */
    private String buildJdbcUrl(DbConnection db) {
        String dbType = db.getDbType();
        if (dbType == null) {
            dbType = "postgresql";
        }
        return switch (dbType.toLowerCase()) {
            case "postgresql" -> String.format("jdbc:postgresql://%s:%d/%s",
                    db.getHost(), db.getPort(), db.getDatabaseName());
            case "mysql" -> String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true",
                    db.getHost(), db.getPort(), db.getDatabaseName());
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };
    }

    // ==================== 文档资料 ====================

    /**
     * 分页查询文档列表
     * @param projectId 项目ID
     * @param query 分页查询参数
     * @return 分页后的文档列表，按上传时间倒序排列
     */
    @GetMapping("/documents")
    @Operation(summary = "分页查询文档列表", description = "查询指定项目下的所有上传文档，支持分页")
    public Result<PageResult<Document>> listDocuments(
            @Parameter(description = "项目ID", required = true)
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

    /**
     * 获取文档详情
     * @param projectId 项目ID
     * @param id 文档ID
     * @return 文档详情
     */
    @GetMapping("/documents/{id}")
    @Operation(summary = "获取文档详情", description = "根据ID获取上传文档的详细信息")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "404", description = "文档不存在")
    })
    public Result<Document> getDocument(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "文档ID", required = true)
            @PathVariable String id) {
        Document doc = documentRepository.selectById(id);
        if (doc == null || !doc.getProjectId().equals(projectId)) {
            return Result.error("文档不存在");
        }
        return Result.success(doc);
    }

    /**
     * 上传文档
     * 上传文档文件到服务器，支持多种文档格式：Word、PDF、Markdown、源码文件等
     * @param projectId 项目ID
     * @param file 上传的文件
     * @param docType 文档类型（可选）
     * @return 新上传文档的ID
     * @throws IOException 文件读写异常
     */
    @PostMapping("/documents/upload")
    @Operation(summary = "上传文档", description = "上传文档文件到项目，支持Word、PDF、Markdown、源码等多种格式")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "上传成功，返回文档ID"),
            @ApiResponse(responseCode = "400", description = "文件为空、大小超限或不支持的文件类型")
    })
    public Result<String> uploadDocument(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "上传的文件", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "文档类型，可选")
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

    /**
     * 获取文件扩展名
     * @param fileName 文件名
     * @return 文件扩展名，包含点号
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return fileName.substring(lastDotIndex);
    }

    /**
     * 根据扩展名获取文件类型分类
     * @param extension 文件扩展名
     * @return 文件类型分类
     */
    private String getFileType(String extension) {
        return switch (extension) {
            case ".docx" -> "DOCX";
            case ".pdf" -> "PDF";
            case ".md" -> "MD";
            case ".txt" -> "TXT";
            default -> "OTHER";
        };
    }

    /**
     * 解析文档
     * 对已上传的文档进行解析，抽取知识事实
     * @param projectId 项目ID
     * @param id 文档ID
     * @return 解析结果，包含抽取到的事实数量
     */
    @PostMapping("/documents/{id}/parse")
    @Operation(summary = "解析文档", description = "对已上传的文档进行解析，抽取知识事实用于知识图谱构建")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "解析完成，返回事实数量"),
            @ApiResponse(responseCode = "404", description = "文档不存在")
    })
    public Result<Map<String, Object>> parseDocument(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "文档ID", required = true)
            @PathVariable String id) {
        Document doc = documentRepository.selectById(id);
        if (doc == null || !doc.getProjectId().equals(projectId)) {
            return Result.error("文档不存在");
        }

        doc.setParseStatus("PARSING");
        documentRepository.updateById(doc);

        Map<String, Object> result = new HashMap<>();
        try {
            // 使用 DocumentExtractor 真实抽取文本并切片
            DocumentExtractor extractor = new DocumentExtractor();
            File file = new java.io.File(doc.getFilePath());
            String text = extractor.extractText(file);

            // 切片并保存到数据库
            List<DocumentExtractor.DocumentChunk> chunks = extractor.chunkDocument(text, doc.getDocName(), 500);

            // 保存所有切片
            for (DocumentExtractor.DocumentChunk chunk : chunks) {
                io.github.legacygraph.entity.DocChunk docChunk = new io.github.legacygraph.entity.DocChunk();
                docChunk.setProjectId(projectId);
                docChunk.setVersionId(doc.getVersionId());
                docChunk.setDocName(doc.getDocName());
                docChunk.setDocPath(doc.getFilePath());
                docChunk.setChunkIndex(chunk.getIndex());
                docChunk.setTitlePath(chunk.getTitlePath());
                docChunk.setContent(chunk.getContent());
                docChunk.setTokenCount(chunk.getTokenCount());
                docChunkRepository.insert(docChunk);
            }

            doc.setParseStatus("PARSED");
            doc.setFactCount(chunks.size());
            doc.setParsedAt(LocalDateTime.now());
            doc.setUpdatedAt(LocalDateTime.now());
            documentRepository.updateById(doc);

            result.put("factCount", chunks.size());
            result.put("chunkCount", chunks.size());
            result.put("message", "解析完成，共生成 " + chunks.size() + " 个文本片段");
            log.info("Document parsed successfully: {}, {} chunks", doc.getDocName(), chunks.size());
        } catch (Exception e) {
            doc.setParseStatus("PARSE_FAILED");
            documentRepository.updateById(doc);
            result.put("success", false);
            result.put("message", "解析失败: " + e.getMessage());
            log.error("Document parsing failed", e);
        }

        return Result.success(result);
    }

    /**
     * 删除文档
     * @param projectId 项目ID
     * @param id 文档ID
     * @return 成功结果
     */
    @DeleteMapping("/documents/{id}")
    @Operation(summary = "删除文档", description = "删除指定的已上传文档")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "文档不存在")
    })
    public Result<Void> deleteDocument(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "文档ID", required = true)
            @PathVariable String id) {
        Document doc = documentRepository.selectById(id);
        if (doc == null || !doc.getProjectId().equals(projectId)) {
            return Result.error("文档不存在");
        }
        documentRepository.deleteById(id);
        return Result.success();
    }
}
