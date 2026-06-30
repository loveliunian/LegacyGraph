package io.github.legacygraph.task;

import io.github.legacygraph.agent.DbSchemaAnalysisAgent;
import io.github.legacygraph.builder.FrontendGraphBuilder;
import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.dto.DbSchemaAnalysis;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.extractors.DatabaseMetadataExtractor;
import io.github.legacygraph.extractors.FrontendApiExtractor;
import io.github.legacygraph.extractors.JavaControllerExtractor;
import io.github.legacygraph.extractors.MyBatisXmlExtractor;
import io.github.legacygraph.extractors.ServiceCallExtractor;
import io.github.legacygraph.extractors.SqlTableExtractor;
import io.github.legacygraph.extractors.VueRouteExtractor;
import io.github.legacygraph.model.ApiFact;
import io.github.legacygraph.model.FrontendPageFact;
import io.github.legacygraph.model.MapperSqlFact;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.DbConnectionRepository;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import javax.sql.DataSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 项目扫描器
 * 协调整个扫描过程，分发到各个抽取器，最后构建图谱。
 * 同时自动发现：数据库连接配置、前后端子路径、文档文件。
 */
@Slf4j
@Component
public class ProjectScanner {

    private final ScanVersionRepository scanVersionRepository;
    private final ScanTaskRepository scanTaskRepository;
    private final FactRepository factRepository;
    private final DbConnectionRepository dbConnectionRepository;
    private final CodeRepoRepository codeRepoRepository;
    private final DocumentRepository documentRepository;
    private final GraphBuilder graphBuilder;
    private final FrontendGraphBuilder frontendGraphBuilder;
    private final ObjectMapper objectMapper;
    private final AiScanOrchestrator aiScanOrchestrator;
    private final DbSchemaAnalysisAgent dbSchemaAnalysisAgent;

    /** 图谱/报告缓存失效器（可选）：重新扫描前清空旧图谱只读缓存，避免读到陈旧数据 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.legacygraph.service.GraphCacheInvalidator graphCacheInvalidator;

    /** 后端项目标志文件 */
    private static final List<String> BACKEND_INDICATORS = List.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "src/main/java", "src/main/kotlin", "src/main/resources"
    );
    /** 前端项目标志文件 */
    private static final List<String> FRONTEND_INDICATORS = List.of(
            "package.json", "vite.config.ts", "vite.config.js",
            "src/App.vue", "src/main.ts", "src/main.js", "src/app"
    );
    /** 文档文件扩展名 */
    private static final Set<String> DOC_EXTENSIONS = Set.of(
            ".md", ".pdf", ".docx", ".txt", ".rst", ".adoc"
    );
    /** JDBC URL 匹配正则 */
    private static final Pattern JDBC_URL_PATTERN = Pattern.compile(
            "jdbc:(postgresql|mysql|mariadb|oracle|sqlserver|h2)://([^:/]+):?(\\d+)?/(\\w+[^?\\s]*)",
            Pattern.CASE_INSENSITIVE);

    public ProjectScanner(ScanVersionRepository scanVersionRepository,
                         ScanTaskRepository scanTaskRepository,
                         FactRepository factRepository,
                         DbConnectionRepository dbConnectionRepository,
                         CodeRepoRepository codeRepoRepository,
                         DocumentRepository documentRepository,
                         GraphBuilder graphBuilder,
                         FrontendGraphBuilder frontendGraphBuilder,
                         ObjectMapper objectMapper,
                         AiScanOrchestrator aiScanOrchestrator,
                         DbSchemaAnalysisAgent dbSchemaAnalysisAgent) {
        this.scanVersionRepository = scanVersionRepository;
        this.scanTaskRepository = scanTaskRepository;
        this.factRepository = factRepository;
        this.dbConnectionRepository = dbConnectionRepository;
        this.codeRepoRepository = codeRepoRepository;
        this.documentRepository = documentRepository;
        this.graphBuilder = graphBuilder;
        this.frontendGraphBuilder = frontendGraphBuilder;
        this.objectMapper = objectMapper;
        this.aiScanOrchestrator = aiScanOrchestrator;
        this.dbSchemaAnalysisAgent = dbSchemaAnalysisAgent;
    }

    /**
     * 异步启动完整扫描流程
     */
    @Async
    public void startFullScan(String projectId, String versionId, String baseDir) {
        log.info("Starting full scan: projectId={}, versionId={}, baseDir={}", projectId, versionId, baseDir);

        // 重新扫描会重建图谱，先失效该版本的图谱/报告只读缓存、进度缓存与项目概览
        if (graphCacheInvalidator != null) {
            graphCacheInvalidator.invalidateVersion(versionId);
            graphCacheInvalidator.invalidateProjectOverview(projectId);
        }

        ScanVersion version = scanVersionRepository.getById(versionId);
        if (version != null) {
            version.setScanStatus("RUNNING");
            version.setStartedAt(LocalDateTime.now());
            scanVersionRepository.updateById(version);
        }

        try {
            // 读取 CodeRepo 配置，获取子路径和 include/exclude 规则
            List<CodeRepo> repos = codeRepoRepository.selectList(
                    new LambdaQueryWrapper<CodeRepo>()
                            .eq(CodeRepo::getProjectId, projectId)
            );
            String backendDir = baseDir;
            String frontendDir = baseDir;
            if (!repos.isEmpty() && baseDir != null) {
                CodeRepo repo = repos.get(0);
                if (repo.getBackendSubPath() != null && !repo.getBackendSubPath().isBlank()) {
                    backendDir = Paths.get(baseDir, repo.getBackendSubPath()).toString();
                    log.info("Using backend sub-path: {}", backendDir);
                }
                if (repo.getFrontendSubPath() != null && !repo.getFrontendSubPath().isBlank()) {
                    frontendDir = Paths.get(baseDir, repo.getFrontendSubPath()).toString();
                    log.info("Using frontend sub-path: {}", frontendDir);
                }
            }

            // === 0. 自动发现：数据库连接、子路径、文档 ===
            // 0a. 从代码中自动发现数据库连接配置
            ScanTask dbDiscoveryTask = createTask(projectId, versionId, "DB_DISCOVERY", "数据库连接自动发现");
            int dbCount = discoverDbConnections(projectId, baseDir);
            completeTask(dbDiscoveryTask, "Discovered " + dbCount + " database connections", null);
            log.info("Auto-discovered {} database connections", dbCount);

            // 0b. 自动检测前后端子路径，回填 CodeRepo
            ScanTask pathDiscoveryTask = createTask(projectId, versionId, "PATH_DISCOVERY", "前后端路径自动检测");
            int pathCount = discoverSubPaths(projectId, baseDir);
            completeTask(pathDiscoveryTask, "Updated " + pathCount + " repo sub-paths", null);
            log.info("Auto-detected sub-paths for {} repos", pathCount);

            // 0c. 自动发现文档文件
            ScanTask docDiscoveryTask = createTask(projectId, versionId, "DOC_DISCOVERY", "文档自动发现");
            int docCount = discoverDocuments(projectId, versionId, baseDir);
            completeTask(docDiscoveryTask, "Discovered " + docCount + " documents", null);
            log.info("Auto-discovered {} documents", docCount);

            // 1. 扫描Java文件获取Controller接口
            ScanTask javaTask = createTask(projectId, versionId, "BACKEND_SCAN", "Java代码扫描");
            int apiCount = scanJavaControllers(projectId, versionId, backendDir, javaTask);
            completeTask(javaTask, "Scanned " + apiCount + " APIs", null);
            log.info("Completed Java controller scan, found {} APIs", apiCount);

            // 1.5 扫描Service调用关系
            ScanTask serviceCallTask = createTask(projectId, versionId, "SERVICE_CALL_SCAN", "Service调用关系扫描");
            int callCount = scanServiceCalls(projectId, versionId, backendDir, serviceCallTask);
            completeTask(serviceCallTask, "Scanned " + callCount + " service call relations", null);
            log.info("Completed service call scan, found {} call relations", callCount);

            // 2. 扫描MyBatis XML文件
            ScanTask mapperTask = createTask(projectId, versionId, "MAPPER_SCAN", "MyBatis XML扫描");
            int mapperCount = scanMyBatisXml(projectId, versionId, backendDir, mapperTask);
            completeTask(mapperTask, "Scanned " + mapperCount + " mappers", null);
            log.info("Completed MyBatis XML scan, found {} mappers", mapperCount);

            // 3. 扫描前端文件 (Vue路由和API调用)
            ScanTask frontendTask = createTask(projectId, versionId, "FRONTEND_SCAN", "前端文件扫描");
            int frontendCount = scanFrontendFiles(projectId, versionId, frontendDir, frontendTask);
            completeTask(frontendTask, "Scanned " + frontendCount + " frontend pages/APIs", null);
            log.info("Completed frontend file scan, found {} pages/APIs", frontendCount);

            // 4. 扫描所有已配置数据库的元数据
            List<DbConnection> dbConnections = dbConnectionRepository.selectList(
                    new LambdaQueryWrapper<DbConnection>()
                            .eq(DbConnection::getProjectId, projectId)
                            .eq(DbConnection::getStatus, "READY")
            );

            if (!dbConnections.isEmpty()) {
                ScanTask dbTask = createTask(projectId, versionId, "DATABASE_SCAN", "数据库元数据扫描");
                int totalTables = 0;
                for (DbConnection conn : dbConnections) {
                    try {
                        DataSource dataSource = createDataSource(conn);
                        totalTables += scanDatabaseMetadata(projectId, versionId, dataSource, conn.getSchemaName());
                    } catch (Exception e) {
                        log.warn("Failed to scan database connection {}: {}", conn.getId(), e.getMessage());
                    }
                }
                completeTask(dbTask, "Scanned " + dbConnections.size() + " databases, " + totalTables + " tables", null);
                log.info("Completed database metadata scan, {} databases, {} tables", dbConnections.size(), totalTables);

                // LLM 语义增强：从 Neo4j 构建 Schema 摘要，调用 LLM 分析
                if (totalTables > 0) {
                    try {
                        String schemaText = graphBuilder.buildDbSchemaSummary(projectId, versionId);
                        if (schemaText != null && !schemaText.isBlank()) {
                            enrichDbGraphWithLLM(projectId, versionId, schemaText);
                        }
                    } catch (Exception e) {
                        log.warn("DB schema LLM enrichment failed (non-blocking): {}", e.getMessage());
                    }
                }
            }

            // 5. 图谱已由各 Builder 在扫描过程中直写 Neo4j，无需额外同步步骤
            ScanTask buildTask = createTask(projectId, versionId, "GRAPH_BUILD", "图谱构建");
            completeTask(buildTask, "Graph built in Neo4j", null);

            // 6. 扫描后 AI 编排（按 scanScope 中的 enableAi/autoGenerateTestCase/minConfidence 开关执行）
            try {
                io.github.legacygraph.dto.AiScanConfig aiConfig =
                        io.github.legacygraph.dto.AiScanConfig.fromScanScope(
                                version != null ? version.getScanScope() : null, objectMapper);
                aiScanOrchestrator.orchestrate(projectId, versionId, aiConfig);
            } catch (Exception aiEx) {
                // AI 编排失败不应使整个扫描失败
                log.error("AI orchestration failed (scan still SUCCESS): versionId={}", versionId, aiEx);
            }

            if (version != null) {
                version.setScanStatus("SUCCESS");
                version.setFinishedAt(LocalDateTime.now());
                scanVersionRepository.updateById(version);
            }

            log.info("Full scan completed successfully: versionId={}", versionId);

        } catch (Exception e) {
            log.error("Scan failed: versionId={}", versionId, e);
            if (version != null) {
                version.setScanStatus("FAILED");
                version.setErrorMessage(e.getMessage());
                version.setFinishedAt(LocalDateTime.now());
                scanVersionRepository.updateById(version);
            }
        }
    }

    // ==================== 自动发现 ====================

    /**
     * 从代码中自动发现数据库连接配置
     * 扫描 application*.yml / application*.properties / application*.yaml，提取 datasource 配置
     * 如果同一 (host+port+database) 的连接已存在则更新，否则新建
     * @return 新创建或更新的数据库连接数量
     */
    private int discoverDbConnections(String projectId, String baseDir) {
        if (baseDir == null) return 0;
        int count = 0;
        try {
            Path basePath = Paths.get(baseDir);
            if (!Files.exists(basePath)) return 0;

            // 查找配置文件
            List<Path> configFiles = Files.walk(basePath)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return (name.startsWith("application") || name.startsWith("application-"))
                                && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"));
                    })
                    .limit(10)
                    .collect(Collectors.toList());

            Set<String> discoveredUrls = new HashSet<>(); // 本次扫描去重

            for (Path configFile : configFiles) {
                try {
                    String content = Files.readString(configFile);
                    Map<String, String> dbConfig = extractDatasourceConfig(content, configFile.getFileName().toString());
                    if (dbConfig != null && !dbConfig.isEmpty()) {
                        String jdbcUrl = dbConfig.get("url");
                        if (jdbcUrl != null && discoveredUrls.add(jdbcUrl)) {
                            String dbType = dbConfig.getOrDefault("dbType", "POSTGRESQL").toUpperCase();
                            String schema = dbType.equals("MYSQL") || dbType.equals("MARIADB")
                                    ? "" : dbConfig.getOrDefault("schema", "public");
                            String host = dbConfig.getOrDefault("host", "localhost");
                            int port = Integer.parseInt(dbConfig.getOrDefault("port",
                                    dbType.equals("MYSQL") || dbType.equals("MARIADB") ? "3306" : "5432"));
                            String database = dbConfig.getOrDefault("database", "unknown");

                            // 查找是否已有相同 (host, port, database) 的连接
                            DbConnection existing = dbConnectionRepository.selectOne(
                                    new LambdaQueryWrapper<DbConnection>()
                                            .eq(DbConnection::getProjectId, projectId)
                                            .eq(DbConnection::getHost, host)
                                            .eq(DbConnection::getPort, port)
                                            .eq(DbConnection::getDatabaseName, database)
                            );

                            if (existing != null) {
                                // 更新已有连接
                                existing.setDbType(dbType);
                                existing.setSchemaName(schema);
                                existing.setUsername(dbConfig.getOrDefault("username", existing.getUsername()));
                                existing.setPassword(dbConfig.getOrDefault("password", existing.getPassword()));
                                existing.setConnectionName(autoDbName(dbConfig, configFile));
                                existing.setUpdatedAt(LocalDateTime.now());
                                dbConnectionRepository.updateById(existing);
                                log.info("Updated existing DB connection: {} (id={})", existing.getConnectionName(), existing.getId());
                            } else {
                                // 新建连接
                                DbConnection conn = new DbConnection();
                                conn.setProjectId(projectId);
                                conn.setConnectionName(autoDbName(dbConfig, configFile));
                                conn.setDbType(dbType);
                                conn.setHost(host);
                                conn.setPort(port);
                                conn.setDatabaseName(database);
                                conn.setSchemaName(schema);
                                conn.setUsername(dbConfig.getOrDefault("username", ""));
                                conn.setPassword(dbConfig.getOrDefault("password", ""));
                                conn.setStatus("READY");
                                conn.setCreatedBy("auto-discovery");
                                conn.setCreatedAt(LocalDateTime.now());
                                conn.setUpdatedAt(LocalDateTime.now());
                                dbConnectionRepository.insert(conn);
                                log.info("Auto-discovered DB connection: {} from {}", conn.getConnectionName(), configFile);
                            }
                            count++;
                        }
                    }
                } catch (Exception e) {
                    log.debug("Skipping config file {}: {}", configFile, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("DB connection auto-discovery failed: {}", e.getMessage());
        }
        return count;
    }

    /**
     * 从配置文件内容中提取数据源配置
     */
    private Map<String, String> extractDatasourceConfig(String content, String fileName) {
        Map<String, String> result = new HashMap<>();

        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            parseYamlDatasource(content, result);
        } else {
            parsePropertiesDatasource(content, result);
        }

        // 如果没找到 spring.datasource，尝试从 JDBC URL 正则匹配（跳过注释行）
        if (!result.containsKey("url")) {
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.startsWith("//")) continue;
                Matcher m = JDBC_URL_PATTERN.matcher(trimmed);
                if (m.find()) {
                    result.put("url", resolvePlaceholder(trimmed.substring(m.start()).trim()));
                    result.put("dbType", m.group(1));
                    result.put("host", m.group(2));
                    result.put("port", m.group(3) != null ? m.group(3) : defaultPort(m.group(1)));
                    result.put("database", m.group(4));
                    break; // 取第一个非注释的 URL
                }
            }
        }

        // 解析所有值中的 ${VAR:default} 占位符
        result.replaceAll((k, v) -> resolvePlaceholder(v));

        return result;
    }

    /**
     * 解析 ${ENV_VAR:default_value} 格式的占位符。
     * 优先用系统环境变量，否则取冒号后的默认值；无默认值则保留原字符串。
     */
    private String resolvePlaceholder(String value) {
        if (value == null) return null;
        // 匹配 ${ENV_VAR:default} 或 ${ENV_VAR}
        Matcher m = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}").matcher(value);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String envVar = m.group(1);
            String defaultValue = m.group(2);
            String resolved = System.getenv(envVar);
            if (resolved == null) resolved = System.getProperty(envVar);
            if (resolved == null) resolved = defaultValue;
            if (resolved == null) resolved = m.group(0); // keep original
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void parseYamlDatasource(String content, Map<String, String> result) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(content);
            if (root == null) return;

            Object spring = root.get("spring");
            if (spring instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> springMap = (Map<String, Object>) spring;
                Object ds = springMap.get("datasource");
                if (ds instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dsMap = (Map<String, Object>) ds;
                    // 标准 spring.datasource.{url,username,password,driver-class-name}
                    if (dsMap.get("url") instanceof String url) extractUrlAndParse(result, url);
                    if (dsMap.get("username") instanceof String u) result.put("username", u);
                    if (dsMap.get("password") instanceof String p) result.put("password", p);
                    if (dsMap.get("driver-class-name") instanceof String d) result.put("dbType", driverToDbType(d));
                    if (dsMap.get("driverClassName") instanceof String dc) result.put("dbType", driverToDbType(dc));

                    // Druid 嵌套: spring.datasource.druid.master.{url,username,password}
                    Object druid = dsMap.get("druid");
                    if (druid instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> druidMap = (Map<String, Object>) druid;
                        Object master = druidMap.get("master");
                        if (master instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> masterMap = (Map<String, Object>) master;
                            if (masterMap.get("url") instanceof String mu) extractUrlAndParse(result, mu);
                            if (masterMap.get("username") instanceof String mu) result.putIfAbsent("username", mu);
                            if (masterMap.get("password") instanceof String mp) result.putIfAbsent("password", mp);
                            if (masterMap.get("driver-class-name") instanceof String md) result.putIfAbsent("dbType", driverToDbType(md));
                        }
                    }

                    // Hikari 嵌套: spring.datasource.hikari.{url,username,password}
                    Object hikari = dsMap.get("hikari");
                    if (hikari instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> hikariMap = (Map<String, Object>) hikari;
                        if (hikariMap.get("url") instanceof String hu) extractUrlAndParse(result, hu);
                        if (hikariMap.get("username") instanceof String hu) result.putIfAbsent("username", hu);
                        if (hikariMap.get("password") instanceof String hp) result.putIfAbsent("password", hp);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("YAML parse failed: {}", e.getMessage());
        }
    }

    /** 从 URL 字符串中提取并解析 JDBC 信息 */
    private void extractUrlAndParse(Map<String, String> result, String url) {
        result.put("url", url);
        parseJdbcUrl(url, result);
    }

    private void parsePropertiesDatasource(String content, Map<String, String> result) {
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith("spring.datasource.url")) {
                String url = extractPropertyValue(line);
                result.put("url", url);
                parseJdbcUrl(url, result);
            } else if (line.startsWith("spring.datasource.username")) {
                result.put("username", extractPropertyValue(line));
            } else if (line.startsWith("spring.datasource.password")) {
                result.put("password", extractPropertyValue(line));
            } else if (line.startsWith("spring.datasource.driver-class-name")) {
                result.put("dbType", driverToDbType(extractPropertyValue(line)));
            }
        }
    }

    private String extractPropertyValue(String line) {
        int eq = line.indexOf('=');
        if (eq >= 0) return line.substring(eq + 1).trim();
        int colon = line.indexOf(':');
        if (colon >= 0) return line.substring(colon + 1).trim();
        return "";
    }

    private void parseJdbcUrl(String jdbcUrl, Map<String, String> result) {
        Matcher m = JDBC_URL_PATTERN.matcher(jdbcUrl);
        if (m.find()) {
            result.putIfAbsent("dbType", m.group(1));
            result.putIfAbsent("host", m.group(2));
            result.putIfAbsent("port", m.group(3) != null ? m.group(3) : defaultPort(m.group(1)));
            result.putIfAbsent("database", m.group(4));
        }
    }

    private String defaultPort(String dbType) {
        return switch (dbType.toLowerCase()) {
            case "postgresql" -> "5432";
            case "mysql", "mariadb" -> "3306";
            case "oracle" -> "1521";
            case "sqlserver" -> "1433";
            default -> "5432";
        };
    }

    private String driverToDbType(String driver) {
        if (driver == null) return "POSTGRESQL";
        return switch (driver.toLowerCase()) {
            case "org.postgresql.Driver", "org.postgresql.ds.PGSimpleDataSource" -> "POSTGRESQL";
            case "com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver" -> "MYSQL";
            case "org.mariadb.jdbc.Driver" -> "MARIADB";
            case "oracle.jdbc.OracleDriver" -> "ORACLE";
            case "com.microsoft.sqlserver.jdbc.SQLServerDriver" -> "SQL_SERVER";
            default -> "POSTGRESQL";
        };
    }

    private String autoDbName(Map<String, String> config, Path configFile) {
        String host = config.getOrDefault("host", "");
        String db = config.getOrDefault("database", "");
        if (!db.isEmpty()) return db + "@" + host;
        return "auto-" + configFile.getFileName().toString().replaceAll("\\..*$", "");
    }

    // ==================== 子路径检测 ====================

    /**
     * 自动检测前后端子路径，回填 CodeRepo 的 backendSubPath / frontendSubPath。
     * 仅在 repoType=FULLSTACK 且子路径为空时回填。
     * @return 更新的仓库数量
     */
    private int discoverSubPaths(String projectId, String baseDir) {
        if (baseDir == null) return 0;
        int count = 0;
        try {
            Path basePath = Paths.get(baseDir);
            if (!Files.exists(basePath)) return 0;

            List<CodeRepo> repos = codeRepoRepository.selectList(
                    new LambdaQueryWrapper<CodeRepo>()
                            .eq(CodeRepo::getProjectId, projectId)
            );

            for (CodeRepo repo : repos) {
                boolean updated = false;

                // 全栈项目：检测子路径
                if ("FULLSTACK".equals(repo.getRepoType())) {
                    Path repoPath = repo.getLocalPath() != null && !repo.getLocalPath().isBlank()
                            ? Paths.get(repo.getLocalPath()) : basePath;

                    if (Files.exists(repoPath)) {
                        // 检测后端子路径
                        if (isBlank(repo.getBackendSubPath())) {
                            String backendPath = detectSubPath(repoPath, BACKEND_INDICATORS);
                            if (backendPath != null) {
                                repo.setBackendSubPath(backendPath);
                                updated = true;
                            }
                        }
                        // 检测前端子路径
                        if (isBlank(repo.getFrontendSubPath())) {
                            String frontendPath = detectSubPath(repoPath, FRONTEND_INDICATORS);
                            if (frontendPath != null) {
                                repo.setFrontendSubPath(frontendPath);
                                updated = true;
                            }
                        }
                    }
                }

                if (updated) {
                    repo.setUpdatedAt(LocalDateTime.now());
                    codeRepoRepository.updateById(repo);
                    count++;
                    log.info("Updated sub-paths for repo {}: backend={}, frontend={}",
                            repo.getRepoName(), repo.getBackendSubPath(), repo.getFrontendSubPath());
                }
            }
        } catch (Exception e) {
            log.warn("Sub-path auto-detection failed: {}", e.getMessage());
        }
        return count;
    }

    /**
     * 在指定目录下检测子路径（包含标志文件的直接子目录名）
     */
    private String detectSubPath(Path rootPath, List<String> indicators) {
        try {
            // 先检查根目录本身
            for (String indicator : indicators) {
                if (Files.exists(rootPath.resolve(indicator))) {
                    return ".";
                }
            }
            // 检查一级子目录
            try (var dirs = Files.list(rootPath)) {
                for (Path dir : dirs.toList()) {
                    if (!Files.isDirectory(dir)) continue;
                    for (String indicator : indicators) {
                        if (Files.exists(dir.resolve(indicator))) {
                            return dir.getFileName().toString();
                        }
                    }
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    // ==================== 文档发现 ====================

    /**
     * 自动发现代码仓库中的文档文件，创建 Document 记录。
     * 写入 filePath（绝对路径）、versionId，使自动发现文档可解析。
     * @return 发现的文档数量
     */
    private int discoverDocuments(String projectId, String versionId, String baseDir) {
        if (baseDir == null) return 0;
        int count = 0;
        try {
            Path basePath = Paths.get(baseDir);
            if (!Files.exists(basePath)) return 0;

            // 排除 node_modules, .git, target, dist, build 等目录
            List<Path> docFiles = Files.walk(basePath)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        // 跳过二进制/构建目录中的文件
                        String pathStr = p.toString().toLowerCase();
                        if (pathStr.contains("/node_modules/") || pathStr.contains("/.git/")
                                || pathStr.contains("/target/") || pathStr.contains("/dist/")
                                || pathStr.contains("/build/") || pathStr.contains("/__pycache__/")
                                || pathStr.contains("/.idea/") || pathStr.contains("/.vscode/")) {
                            return false;
                        }
                        // 检查扩展名
                        for (String ext : DOC_EXTENSIONS) {
                            if (name.endsWith(ext)) return true;
                        }
                        return false;
                    })
                    .limit(50)
                    .collect(Collectors.toList());

            Path relativeRoot = basePath.toAbsolutePath();
            for (Path docFile : docFiles) {
                try {
                    String relativePath;
                    try {
                        relativePath = relativeRoot.relativize(docFile.toAbsolutePath()).toString();
                    } catch (IllegalArgumentException e) {
                        relativePath = docFile.getFileName().toString();
                    }

                    // 查找是否已有相同 (projectId, docName) 的文档
                    Document existing = documentRepository.selectOne(
                            new LambdaQueryWrapper<Document>()
                                    .eq(Document::getProjectId, projectId)
                                    .eq(Document::getDocName, relativePath)
                    );

                    if (existing != null) {
                        // 更新已有文档
                        existing.setVersionId(versionId);
                        existing.setFilePath(docFile.toAbsolutePath().toString());
                        existing.setDocType(detectDocType(docFile.getFileName().toString()));
                        existing.setFileType(detectFileType(docFile.getFileName().toString()));
                        try {
                            existing.setFileSize(Files.size(docFile));
                        } catch (IOException ignored) {}
                        existing.setParseStatus("DISCOVERED");
                        existing.setUpdatedAt(LocalDateTime.now());
                        documentRepository.updateById(existing);
                    } else {
                        Document doc = new Document();
                        doc.setProjectId(projectId);
                        doc.setVersionId(versionId);
                        doc.setDocName(relativePath);
                        doc.setFilePath(docFile.toAbsolutePath().toString());
                        doc.setDocType(detectDocType(docFile.getFileName().toString()));
                        doc.setFileType(detectFileType(docFile.getFileName().toString()));
                        try {
                            doc.setFileSize(Files.size(docFile));
                        } catch (IOException ignored) {}
                        doc.setParseStatus("DISCOVERED");
                        doc.setUploadedBy("auto-discovery");
                        doc.setCreatedAt(LocalDateTime.now());
                        doc.setUpdatedAt(LocalDateTime.now());
                        documentRepository.insert(doc);
                    }
                    count++;
                } catch (Exception e) {
                    log.debug("Skip document {}: {}", docFile, e.getMessage());
                }
            }
            log.info("Auto-discovered {} documents in {}", count, baseDir);
        } catch (Exception e) {
            log.warn("Document auto-discovery failed: {}", e.getMessage());
        }
        return count;
    }

    private String detectDocType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md")) return "MARKDOWN";
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".txt")) return "TEXT";
        if (lower.endsWith(".rst")) return "RST";
        if (lower.endsWith(".adoc")) return "ASCIIDOC";
        return "GENERAL";
    }

    private String detectFileType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md")) return "MD";
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".txt")) return "TXT";
        return "OTHER";
    }

    // ==================== 数据源 ====================

    private DataSource createDataSource(DbConnection conn) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        String url = buildJdbcUrl(conn);
        dataSource.setUrl(url);
        dataSource.setUsername(conn.getUsername());
        dataSource.setPassword(conn.getPassword() != null ? conn.getPassword() : "");
        dataSource.setDriverClassName(getDriverClassName(conn.getDbType()));
        return dataSource;
    }

    private String buildJdbcUrl(DbConnection conn) {
        String dbType = conn.getDbType();
        if (dbType == null) dbType = "postgresql";
        return switch (dbType.toLowerCase()) {
            case "postgresql" -> String.format("jdbc:postgresql://%s:%d/%s",
                    conn.getHost(), conn.getPort(), conn.getDatabaseName());
            case "mysql" -> String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true",
                    conn.getHost(), conn.getPort(), conn.getDatabaseName());
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };
    }

    private String getDriverClassName(String dbType) {
        if (dbType == null) return "org.postgresql.Driver";
        return switch (dbType.toLowerCase()) {
            case "postgresql" -> "org.postgresql.Driver";
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            default -> "org.postgresql.Driver";
        };
    }

    // ==================== 代码扫描 ====================

    private int scanJavaControllers(String projectId, String versionId, String baseDir, ScanTask task) {
        if (baseDir == null) return 0;
        JavaControllerExtractor extractor = new JavaControllerExtractor();
        int totalCount = 0;
        try {
            List<Path> javaFiles = Files.walk(Paths.get(baseDir))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> isControllerFile(p))
                    .collect(Collectors.toList());
            for (Path javaFile : javaFiles) {
                try {
                    List<ApiFact> apis = extractor.extractFromFile(javaFile);
                    if (!apis.isEmpty()) {
                        for (ApiFact api : apis) {
                            saveFact(projectId, versionId, "CODE_AST", "API", api.getFullPath(), api.getMethodName(),
                                    javaFile.toString(), api.getStartLine(), api.getEndLine(),
                                    api, BigDecimal.ONE, "EXTRACTED");
                        }
                        graphBuilder.buildApiNodes(projectId, versionId, apis, javaFile.toString());
                        totalCount += apis.size();
                    }
                } catch (IOException e) {
                    log.warn("Failed to parse Java file: {}", javaFile, e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to walk directory for Java scan", e);
        }
        return totalCount;
    }

    private int scanMyBatisXml(String projectId, String versionId, String baseDir, ScanTask task) {
        if (baseDir == null) return 0;
        MyBatisXmlExtractor extractor = new MyBatisXmlExtractor();
        int mapperCount = 0;
        try {
            List<Path> xmlFiles = Files.walk(Paths.get(baseDir))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".xml"))
                    .filter(p -> isMyBatisMapperFile(p))
                    .collect(Collectors.toList());
            for (Path xmlPath : xmlFiles) {
                File xmlFile = xmlPath.toFile();
                MapperSqlFact mapperFact = extractor.extractFromFile(xmlFile);
                if (mapperFact.getNamespace() != null) {
                    saveFact(projectId, versionId, "MAPPER_XML", "MAPPER", mapperFact.getNamespace(),
                            mapperFact.getNamespace(), xmlFile.getAbsolutePath(),
                            null, null, mapperFact, BigDecimal.ONE, "EXTRACTED");
                    graphBuilder.buildMapperSqlGraph(projectId, versionId, mapperFact);
                    mapperCount++;
                }
            }
        } catch (Exception e) {
            log.error("Failed to scan MyBatis XML files", e);
        }
        return mapperCount;
    }

    public int scanDatabaseMetadata(String projectId, String versionId, DataSource dataSource, String schema) {
        DatabaseMetadataExtractor extractor = new DatabaseMetadataExtractor();
        try {
            var tables = extractor.extractFromSchema(dataSource, schema);
            graphBuilder.buildDatabaseGraph(projectId, versionId, tables);
            log.info("Extracted {} tables from database schema {}", tables.size(), schema);
            return tables.size();
        } catch (Exception e) {
            log.error("Failed to extract database metadata", e);
            return 0;
        }
    }

    private int scanFrontendFiles(String projectId, String versionId, String baseDir, ScanTask task) {
        if (baseDir == null) return 0;
        VueRouteExtractor vueExtractor = new VueRouteExtractor();
        FrontendApiExtractor apiExtractor = new FrontendApiExtractor();
        int totalCount = 0;
        try {
            List<Path> vueFiles = Files.walk(Paths.get(baseDir))
                    .filter(Files::isRegularFile)
                    .filter(p -> isVueFile(p))
                    .collect(Collectors.toList());
            for (Path vueFile : vueFiles) {
                try {
                    List<FrontendPageFact> pages = vueExtractor.extractFromFile(vueFile);
                    if (!pages.isEmpty()) {
                        for (FrontendPageFact page : pages) {
                            saveFact(projectId, versionId, "FRONTEND_AST", "FRONTEND_PAGE", page.getRoutePath(),
                                    page.getPageName(), vueFile.toString(), page.getStartLine(), page.getEndLine(),
                                    page, BigDecimal.ONE, "EXTRACTED");
                            totalCount++;
                        }
                        frontendGraphBuilder.buildFrontendGraph(projectId, versionId, pages, vueFile.toString());
                    }
                    List<io.github.legacygraph.model.FrontendPageFact.FrontendApiCall> apiCalls = apiExtractor.extractFromFile(vueFile);
                    if (!apiCalls.isEmpty()) {
                        for (io.github.legacygraph.model.FrontendPageFact.FrontendApiCall api : apiCalls) {
                            saveFact(projectId, versionId, "FRONTEND_AST", "FRONTEND_API", api.getUrl(),
                                    api.getMethod() + " " + api.getUrl(), vueFile.toString(),
                                    api.getLineNumber(), api.getLineNumber(), api, BigDecimal.ONE, "EXTRACTED");
                            totalCount++;
                        }
                        frontendGraphBuilder.buildFrontendApiGraph(projectId, versionId, apiCalls);
                    }
                } catch (IOException e) {
                    log.warn("Failed to parse Vue file: {}", vueFile, e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to walk directory for frontend scan", e);
        }
        return totalCount;
    }

    // ==================== 文件判断 ====================

    private boolean isControllerFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith("Controller.java") || fileName.contains("Controller");
    }

    private int scanServiceCalls(String projectId, String versionId, String baseDir, ScanTask task) {
        if (baseDir == null) return 0;
        ServiceCallExtractor extractor = new ServiceCallExtractor();
        int totalCount = 0;
        try {
            List<Path> javaFiles = Files.walk(Paths.get(baseDir))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> isServiceOrMapperFile(p))
                    .collect(Collectors.toList());
            for (Path javaFile : javaFiles) {
                try {
                    List<ServiceCallExtractor.CallRelation> calls = extractor.extractFromFile(javaFile.toFile());
                    if (!calls.isEmpty()) {
                        for (ServiceCallExtractor.CallRelation call : calls) {
                            saveFact(projectId, versionId, "CODE_AST", "SERVICE_CALL", call.getCallerClass() + "." + call.getCallerMethod(),
                                    call.getCallerClass() + " -> " + call.getTargetClass() + "." + call.getTargetMethod(),
                                    javaFile.toString(), call.getLineNumber(), call.getLineNumber(),
                                    call, BigDecimal.ONE, "EXTRACTED");
                        }
                        graphBuilder.buildServiceCallGraph(projectId, versionId, calls);
                        totalCount += calls.size();
                    }
                } catch (IOException e) {
                    log.warn("Failed to parse Java file for service call: {}", javaFile, e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to walk directory for service call scan", e);
        }
        return totalCount;
    }

    private boolean isMyBatisMapperFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith("Mapper.xml") ||
               fileName.contains("Mapper") && fileName.endsWith(".xml") ||
               fileName.contains("mapper") && fileName.endsWith(".xml");
    }

    private boolean isServiceOrMapperFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith("Service.java") || fileName.contains("Service") ||
               fileName.endsWith("Mapper.java") || fileName.contains("Mapper") ||
               fileName.endsWith("Dao.java") || fileName.contains("Dao");
    }

    private boolean isVueFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".vue") || fileName.endsWith(".jsx") || fileName.endsWith(".tsx");
    }

    // ==================== 工具方法 ====================

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private ScanTask createTask(String projectId, String versionId, String taskType, String taskName) {
        ScanTask task = new ScanTask();
        task.setId(UUID.randomUUID().toString());
        task.setProjectId(projectId);
        task.setVersionId(versionId);
        task.setTaskType(taskType);
        task.setTaskName(taskName);
        task.setTaskStatus("RUNNING");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        scanTaskRepository.insert(task);
        return task;
    }

    private void completeTask(ScanTask task, String summary, String error) {
        try {
            task.setOutputSummary(objectMapper.writeValueAsString(summary));
        } catch (Exception e) {
            task.setOutputSummary("\"" + summary.replace("\"", "\\\"") + "\"");
        }
        task.setErrorMessage(error);
        task.setTaskStatus(error == null ? "SUCCESS" : "FAILED");
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        scanTaskRepository.updateById(task);
    }

    private void saveFact(String projectId, String versionId, String sourceType, String factType, String factKey, String factName,
            String sourcePath, Integer startLine, Integer endLine, Object data,
            BigDecimal confidence, String status) {
        try {
            // 检查是否已存在（唯一约束：project_id + version_id + fact_type + fact_key）
            long exists = factRepository.selectCount(
                    new LambdaQueryWrapper<Fact>()
                            .eq(Fact::getProjectId, projectId)
                            .eq(Fact::getVersionId, versionId)
                            .eq(Fact::getFactType, factType)
                            .eq(Fact::getFactKey, factKey)
            );
            if (exists > 0) {
                return;
            }

            Fact fact = new Fact();
            fact.setId(UUID.randomUUID().toString());
            fact.setProjectId(projectId);
            fact.setVersionId(versionId);
            fact.setFactType(factType);
            fact.setFactKey(factKey);
            fact.setFactName(factName);
            fact.setSourceType(sourceType);
            fact.setSourcePath(sourcePath);
            fact.setStartLine(startLine);
            fact.setEndLine(endLine);
            fact.setNormalizedData(objectMapper.writeValueAsString(data));
            fact.setConfidence(confidence != null ? confidence.doubleValue() : 0.0);
            fact.setStatus(status);
            fact.setCreatedAt(LocalDateTime.now());
            fact.setUpdatedAt(LocalDateTime.now());
            factRepository.insert(fact);
        } catch (Exception e) {
            log.error("Failed to save fact", e);
        }
    }

    @Async
    public void resumeFullScan(String projectId, String versionId, String baseDir) {
        log.info("Resuming full scan: projectId={}, versionId={}, baseDir={}", projectId, versionId, baseDir);
        ScanVersion version = scanVersionRepository.getById(versionId);
        if (version != null) {
            version.setScanStatus("RUNNING");
            scanVersionRepository.updateById(version);
        }
        try {
            startFullScan(projectId, versionId, baseDir);
        } catch (Exception e) {
            log.error("Resume scan failed: versionId={}", versionId, e);
            if (version != null) {
                version.setScanStatus("FAILED");
                version.setErrorMessage(e.getMessage());
                version.setFinishedAt(LocalDateTime.now());
                scanVersionRepository.updateById(version);
            }
        }
    }

    /**
     * 调用 LLM 对数据库 Schema 进行语义分析，并丰富图谱。
     */
    private void enrichDbGraphWithLLM(String projectId, String versionId, String schemaText) {
        log.info("Starting LLM DB schema analysis: projectId={}, versionId={}", projectId, versionId);
        try {
            DbSchemaAnalysis analysis = dbSchemaAnalysisAgent.analyze(projectId, schemaText);
            if (analysis != null) {
                graphBuilder.enrichDbGraphWithLLM(projectId, versionId, analysis);
                log.info("LLM DB schema enrichment completed: projectId={}", projectId);
            }
        } catch (Exception e) {
            log.warn("LLM DB schema analysis failed: {}", e.getMessage());
        }
    }
}
