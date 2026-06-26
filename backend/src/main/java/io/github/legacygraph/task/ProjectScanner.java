package io.github.legacygraph.task;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.extractors.DatabaseMetadataExtractor;
import io.github.legacygraph.extractors.JavaControllerExtractor;
import io.github.legacygraph.extractors.MyBatisXmlExtractor;
import io.github.legacygraph.extractors.SqlTableExtractor;
import io.github.legacygraph.model.ApiFact;
import io.github.legacygraph.model.MapperSqlFact;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 项目扫描器
 * 协调整个扫描过程，分发到各个抽取器，最后构建图谱
 */
@Slf4j
@Component
public class ProjectScanner {

    private final ScanVersionRepository scanVersionRepository;
    private final ScanTaskRepository scanTaskRepository;
    private final FactRepository factRepository;
    private final GraphBuilder graphBuilder;
    private final ObjectMapper objectMapper;

    public ProjectScanner(ScanVersionRepository scanVersionRepository,
                         ScanTaskRepository scanTaskRepository,
                         FactRepository factRepository,
                         GraphBuilder graphBuilder,
                         ObjectMapper objectMapper) {
        this.scanVersionRepository = scanVersionRepository;
        this.scanTaskRepository = scanTaskRepository;
        this.factRepository = factRepository;
        this.graphBuilder = graphBuilder;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步启动完整扫描流程
     */
    @Async
    @Transactional
    public void startFullScan(String projectId, String versionId, String baseDir) {
        log.info("Starting full scan: projectId={}, versionId={}, baseDir={}", projectId, versionId, baseDir);

        // 更新扫描状态为运行中
        ScanVersion version = scanVersionRepository.getById(versionId);
        if (version != null) {
            version.setScanStatus("RUNNING");
            version.setStartedAt(LocalDateTime.now());
            scanVersionRepository.updateById(version);
        }

        try {
            // 1. 扫描Java文件获取Controller接口
            ScanTask javaTask = createTask(projectId, versionId, "BACKEND_SCAN", "Java代码扫描");
            int apiCount = scanJavaControllers(projectId, versionId, baseDir, javaTask);
            completeTask(javaTask, "Scanned " + apiCount + " APIs", null);
            log.info("Completed Java controller scan, found {} APIs", apiCount);

            // 2. 扫描MyBatis XML文件
            ScanTask mapperTask = createTask(projectId, versionId, "MAPPER_SCAN", "MyBatis XML扫描");
            int mapperCount = scanMyBatisXml(projectId, versionId, baseDir, mapperTask);
            completeTask(mapperTask, "Scanned " + mapperCount + " mappers", null);
            log.info("Completed MyBatis XML scan, found {} mappers", mapperCount);

            // 3. TODO: 扫描前端文件

            // 4. TODO: 扫描数据库元数据

            // 5. 构建图谱
            ScanTask buildTask = createTask(projectId, versionId, "GRAPH_BUILD", "图谱构建");
            // GraphBuilder已经在抽取过程中逐步构建了，这里只需要同步到Neo4j
            graphBuilder.syncToNeo4j(projectId, versionId);
            completeTask(buildTask, "Graph built and synced to Neo4j", null);

            // 完成扫描
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

    /**
     * 扫描Java Controller文件
     */
    private int scanJavaControllers(String projectId, String versionId, String baseDir, ScanTask task) {
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
                        // 保存事实
                        for (ApiFact api : apis) {
                            saveFact(projectId, versionId, "API", api.getFullPath(), api.getMethodName(),
                                    javaFile.toString(), api.getStartLine(), api.getEndLine(),
                                    api, BigDecimal.ONE, "EXTRACTED");
                        }
                        // 构建图谱节点
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

    /**
     * 扫描MyBatis XML文件
     */
    private int scanMyBatisXml(String projectId, String versionId, String baseDir, ScanTask task) {
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
                    saveFact(projectId, versionId, "MAPPER", mapperFact.getNamespace(),
                            mapperFact.getNamespace(), xmlFile.getAbsolutePath(),
                            null, null, mapperFact, BigDecimal.ONE, "EXTRACTED");

                    // 处理每个SQL语句，构建图谱
                    graphBuilder.buildMapperSqlGraph(projectId, versionId, mapperFact);
                    mapperCount++;
                }
            }

        } catch (Exception e) {
            log.error("Failed to scan MyBatis XML files", e);
        }

        return mapperCount;
    }

    /**
     * 扫描数据库元数据
     */
    public void scanDatabaseMetadata(String projectId, String versionId, DataSource dataSource, String schema) {
        DatabaseMetadataExtractor extractor = new DatabaseMetadataExtractor();
        try {
            var tables = extractor.extractFromSchema(dataSource, schema);
            graphBuilder.buildDatabaseGraph(projectId, versionId, tables);
            log.info("Extracted {} tables from database schema {}", tables.size(), schema);
        } catch (Exception e) {
            log.error("Failed to extract database metadata", e);
        }
    }

    /**
     * 判断是否是Controller文件
     */
    private boolean isControllerFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith("Controller.java") || fileName.contains("Controller");
    }

    /**
     * 判断是否是MyBatis Mapper XML文件
     */
    private boolean isMyBatisMapperFile(Path path) {
        String fileName = path.getFileName().toString();
        // 常见命名格式: *Mapper.xml
        return fileName.endsWith("Mapper.xml") ||
               fileName.contains("Mapper") && fileName.endsWith(".xml") ||
               fileName.contains("mapper") && fileName.endsWith(".xml");
    }

    /**
     * 创建扫描任务
     */
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
        scanTaskRepository.save(task);
        return task;
    }

    /**
     * 完成任务
     */
    private void completeTask(ScanTask task, String summary, String error) {
        task.setOutputSummary(summary);
        task.setErrorMessage(error);
        task.setTaskStatus(error == null ? "SUCCESS" : "FAILED");
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        scanTaskRepository.updateById(task);
    }

    /**
     * 保存抽取的事实
     */
    private void saveFact(String projectId, String versionId, String factType, String factKey, String factName,
            String sourcePath, Integer startLine, Integer endLine, Object data,
            BigDecimal confidence, String status) {
        try {
            Fact fact = new Fact();
            fact.setId(UUID.randomUUID().toString());
            fact.setProjectId(projectId);
            fact.setVersionId(versionId);
            fact.setFactType(factType);
            fact.setFactKey(factKey);
            fact.setFactName(factName);
            fact.setSourceType("CODE_AST");
            fact.setSourcePath(sourcePath);
            fact.setStartLine(startLine);
            fact.setEndLine(endLine);
            fact.setNormalizedData(objectMapper.writeValueAsString(data));
            fact.setConfidence(confidence);
            fact.setStatus(status);
            fact.setCreatedAt(LocalDateTime.now());
            fact.setUpdatedAt(LocalDateTime.now());
            factRepository.save(fact);
        } catch (Exception e) {
            log.error("Failed to save fact", e);
        }
    }
}
