package io.github.legacygraph.service.scan;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.extractors.DatabaseConstraintExtractor;
import io.github.legacygraph.extractors.DatabaseMetadataExtractor;
import io.github.legacygraph.extractors.DatabaseMetadataExtractor.TableMetadata;
import io.github.legacygraph.repository.DbConnectionRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DatabaseMetadataScanService {

    private final GraphBuilder graphBuilder;
    private final DatabaseConstraintExtractor databaseConstraintExtractor;
    private final DbConnectionRepository dbConnectionRepository;

    public DatabaseMetadataScanService(GraphBuilder graphBuilder) {
        this(graphBuilder, new DatabaseConstraintExtractor(), null);
    }

    public DatabaseMetadataScanService(GraphBuilder graphBuilder,
                                       DatabaseConstraintExtractor databaseConstraintExtractor) {
        this(graphBuilder, databaseConstraintExtractor, null);
    }

    @Autowired
    public DatabaseMetadataScanService(GraphBuilder graphBuilder,
                                       DatabaseConstraintExtractor databaseConstraintExtractor,
                                       DbConnectionRepository dbConnectionRepository) {
        this.graphBuilder = graphBuilder;
        this.databaseConstraintExtractor = databaseConstraintExtractor;
        this.dbConnectionRepository = dbConnectionRepository;
    }

    public int scan(String projectId, String versionId, DataSource dataSource, String schema, String dbType) {
        return scan(projectId, versionId, dataSource, schema, dbType,
                Collections.emptyList(), Collections.emptyList(), null, 0);
    }

    public int scan(String projectId, String versionId, DataSource dataSource, DbConnection connection) {
        return scan(projectId, versionId, dataSource, connection, 0);
    }

    public int scan(String projectId, String versionId, DataSource dataSource,
                    DbConnection connection, int maxTables) {
        if (connection == null) {
            return scan(projectId, versionId, dataSource, null, null);
        }
        return scan(projectId, versionId, dataSource,
                connection.getSchemaName(),
                connection.getDbType(),
                parsePatterns(connection.getIncludeTables()),
                parsePatterns(connection.getExcludeTables()),
                connection,
                maxTables);
    }

    private int scan(String projectId, String versionId, DataSource dataSource,
                     String schema, String dbType,
                     List<String> includeTables,
                     List<String> excludeTables,
                     DbConnection connection,
                     int maxTables) {
        String effectiveSchema = (schema != null && !schema.isBlank()) ? schema : "public";
        boolean isMySql = "mysql".equalsIgnoreCase(dbType) || "mariadb".equalsIgnoreCase(dbType);
        log.info("Scan still running: projectId={}, versionId={}, phase=DATABASE_SCAN, detail=extracting schema {} (raw: {}, dbType: {}, isMySql: {})",
                projectId, versionId, effectiveSchema, schema, dbType, isMySql);
        DatabaseMetadataExtractor extractor = new DatabaseMetadataExtractor();
        try {
            // extractFromSchema 内部会自动检测并修正 isMySql
            var tables = extractor.extractFromSchema(dataSource, effectiveSchema, isMySql);
            var filteredTables = tables.stream()
                    .filter(table -> shouldScanTable(table.getTableName(), includeTables, excludeTables))
                    .toList();
            var limitedTables = limitTables(filteredTables, maxTables);
            Set<String> scannedTableNames = limitedTables.stream()
                    .map(TableMetadata::getTableName)
                    .collect(Collectors.toCollection(HashSet::new));
            String fingerprint = computeSchemaFingerprint(limitedTables);
            if (connection != null && fingerprint.equals(connection.getSchemaFingerprint())) {
                updateConnectionScanSnapshot(connection, limitedTables.size(), fingerprint);
                log.info("DB schema unchanged, but still rebuilding graph for consistency: connectionId={}, schema={}, tables={}",
                        connection.getId(), effectiveSchema, limitedTables.size());
                // 即使 fingerprint 相同也重建图谱，确保数据一致性
            } else {
                log.info("DB schema changed or first scan, rebuilding graph: connectionId={}, schema={}, tables={}",
                        connection != null ? connection.getId() : "null", effectiveSchema, limitedTables.size());
            }
            
            graphBuilder.buildDatabaseGraph(projectId, versionId, limitedTables);
            buildConstraintGraph(projectId, versionId, dataSource, effectiveSchema, isMySql,
                    includeTables, excludeTables, scannedTableNames);
            updateConnectionScanSnapshot(connection, limitedTables.size(), fingerprint);
            log.info("Extracted {} tables from database schema {} ({} filtered out, {} limited out)",
                    limitedTables.size(), effectiveSchema, tables.size() - filteredTables.size(),
                    filteredTables.size() - limitedTables.size());
            return limitedTables.size();
        } catch (Exception e) {
            log.error("Failed to extract database metadata for schema {}", effectiveSchema, e);
            return 0;
        }
    }

    private void buildConstraintGraph(String projectId, String versionId, DataSource dataSource,
                                      String effectiveSchema, boolean isMySql) {
        buildConstraintGraph(projectId, versionId, dataSource, effectiveSchema, isMySql,
                Collections.emptyList(), Collections.emptyList());
    }

    private void buildConstraintGraph(String projectId, String versionId, DataSource dataSource,
                                      String effectiveSchema, boolean isMySql,
                                      List<String> includeTables,
                                      List<String> excludeTables) {
        buildConstraintGraph(projectId, versionId, dataSource, effectiveSchema, isMySql,
                includeTables, excludeTables, Set.of());
    }

    private void buildConstraintGraph(String projectId, String versionId, DataSource dataSource,
                                      String effectiveSchema, boolean isMySql,
                                      List<String> includeTables,
                                      List<String> excludeTables,
                                      Set<String> scannedTableNames) {
        try {
            // === 自动检测实际数据库类型，避免配置错误导致约束抽取失败 ===
            boolean actualIsMySql = isMySql;
            try (var conn = dataSource.getConnection()) {
                String productName = conn.getMetaData().getDatabaseProductName();
                if (productName != null) {
                    String lower = productName.toLowerCase();
                    actualIsMySql = lower.contains("mysql") || lower.contains("mariadb");
                    if (actualIsMySql != isMySql) {
                        log.warn("buildConstraintGraph: 数据库类型自动检测与配置不一致! JDBC报告: {} (isMySql={}), 配置标记: isMySql={}, 以JDBC为准",
                                productName, actualIsMySql, isMySql);
                    }
                }
            }
            String catalog = actualIsMySql ? effectiveSchema : null;
            String schemaPattern = actualIsMySql ? null : effectiveSchema;
            var foreignKeys = databaseConstraintExtractor.extractForeignKeys(dataSource, catalog, schemaPattern)
                    .stream()
                    .filter(fk -> shouldScanTable(fk.getFkTableName(), includeTables, excludeTables)
                            && shouldScanTable(fk.getPkTableName(), includeTables, excludeTables))
                    .filter(fk -> shouldKeepConstraintTable(fk.getFkTableName(), scannedTableNames)
                            && shouldKeepConstraintTable(fk.getPkTableName(), scannedTableNames))
                    .toList();
            var indexes = databaseConstraintExtractor.extractIndexes(dataSource, catalog, schemaPattern)
                    .stream()
                    .filter(index -> shouldScanTable(index.getTableName(), includeTables, excludeTables))
                    .filter(index -> shouldKeepConstraintTable(index.getTableName(), scannedTableNames))
                    .toList();
            graphBuilder.buildDatabaseConstraintGraph(projectId, versionId, effectiveSchema, foreignKeys, indexes);
            log.info("Extracted {} foreign keys and {} index entries from database schema {}",
                    foreignKeys.size(), indexes.size(), effectiveSchema);
        } catch (Exception e) {
            log.error("Failed to extract database constraints for schema {}: {}", effectiveSchema, e.getMessage(), e);
            // 不静默：记录到扫描任务状态，让前端可见
            throw new RuntimeException("数据库约束提取失败: schema=" + effectiveSchema + ", 错误=" + e.getMessage(), e);
        }
    }

    private static List<TableMetadata> limitTables(List<TableMetadata> tables, int maxTables) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        if (maxTables <= 0 || tables.size() <= maxTables) {
            return tables;
        }
        return tables.stream().limit(maxTables).toList();
    }

    private static boolean shouldKeepConstraintTable(String tableName, Set<String> scannedTableNames) {
        return scannedTableNames == null || scannedTableNames.isEmpty() || scannedTableNames.contains(tableName);
    }

    /**
     * 计算 schema fingerprint：表名 + 列名 + 数据类型 + 可空性 的 SHA-256。
     */
    public static String computeSchemaFingerprint(List<TableMetadata> tables) {
        if (tables == null || tables.isEmpty()) {
            return "";
        }
        String payload = tables.stream()
                .sorted(Comparator.comparing(TableMetadata::getTableName))
                .map(table -> table.getTableName() + ":" + table.getColumns().stream()
                        .sorted(Comparator.comparing(col -> col.getColumnName() != null ? col.getColumnName() : ""))
                        .map(col -> (col.getColumnName() != null ? col.getColumnName() : "") + ":"
                                + (col.getDataType() != null ? col.getDataType() : "") + ":"
                                + (Boolean.TRUE.equals(col.getNullable()) ? "Y" : "N"))
                        .collect(Collectors.joining(",")))
                .collect(Collectors.joining("|"));
        return DigestUtils.sha256Hex(payload);
    }

    /**
     * 判断是否应扫描指定表（include/exclude 过滤）。
     */
    public static boolean shouldScanTable(String tableName, List<String> includes, List<String> excludes) {
        boolean included = includes == null || includes.isEmpty()
                || includes.stream().anyMatch(pattern -> wildcardMatch(pattern, tableName));
        boolean excluded = excludes != null
                && excludes.stream().anyMatch(pattern -> wildcardMatch(pattern, tableName));
        return included && !excluded;
    }

    private static boolean wildcardMatch(String pattern, String text) {
        if (text == null) return false;
        if ("*".equals(pattern)) return true;
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return text.matches(regex);
    }

    private static List<String> parsePatterns(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isBlank())
                .toList();
    }

    private void updateConnectionScanSnapshot(DbConnection connection, int tableCount, String fingerprint) {
        if (connection == null || dbConnectionRepository == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        connection.setTableCount(tableCount);
        connection.setLastScanTime(now);
        connection.setSchemaFingerprint(fingerprint);
        connection.setSchemaFingerprintUpdatedAt(now);
        connection.setUpdatedAt(now);
        dbConnectionRepository.updateById(connection);
    }
}
