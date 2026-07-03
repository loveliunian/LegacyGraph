package io.github.legacygraph.service;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.DatabaseConstraintExtractor;
import io.github.legacygraph.extractors.DatabaseMetadataExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

@Slf4j
@Service
public class DatabaseMetadataScanService {

    private final GraphBuilder graphBuilder;
    private final DatabaseConstraintExtractor databaseConstraintExtractor;

    public DatabaseMetadataScanService(GraphBuilder graphBuilder) {
        this(graphBuilder, new DatabaseConstraintExtractor());
    }

    @Autowired
    public DatabaseMetadataScanService(GraphBuilder graphBuilder, DatabaseConstraintExtractor databaseConstraintExtractor) {
        this.graphBuilder = graphBuilder;
        this.databaseConstraintExtractor = databaseConstraintExtractor;
    }

    public int scan(String projectId, String versionId, DataSource dataSource, String schema, String dbType) {
        String effectiveSchema = (schema != null && !schema.isBlank()) ? schema : "public";
        boolean isMySql = "mysql".equalsIgnoreCase(dbType) || "mariadb".equalsIgnoreCase(dbType);
        log.info("Scan still running: projectId={}, versionId={}, phase=DATABASE_SCAN, detail=extracting schema {} (raw: {}, dbType: {}, isMySql: {})",
                projectId, versionId, effectiveSchema, schema, dbType, isMySql);
        DatabaseMetadataExtractor extractor = new DatabaseMetadataExtractor();
        try {
            var tables = extractor.extractFromSchema(dataSource, effectiveSchema, isMySql);
            graphBuilder.buildDatabaseGraph(projectId, versionId, tables);
            buildConstraintGraph(projectId, versionId, dataSource, effectiveSchema, isMySql);
            log.info("Extracted {} tables from database schema {}", tables.size(), effectiveSchema);
            return tables.size();
        } catch (Exception e) {
            log.error("Failed to extract database metadata for schema {}", effectiveSchema, e);
            return 0;
        }
    }

    private void buildConstraintGraph(String projectId, String versionId, DataSource dataSource,
                                      String effectiveSchema, boolean isMySql) {
        try {
            String catalog = isMySql ? effectiveSchema : null;
            String schemaPattern = isMySql ? null : effectiveSchema;
            var foreignKeys = databaseConstraintExtractor.extractForeignKeys(dataSource, catalog, schemaPattern);
            var indexes = databaseConstraintExtractor.extractIndexes(dataSource, catalog, schemaPattern);
            graphBuilder.buildDatabaseConstraintGraph(projectId, versionId, effectiveSchema, foreignKeys, indexes);
            log.info("Extracted {} foreign keys and {} index entries from database schema {}",
                    foreignKeys.size(), indexes.size(), effectiveSchema);
        } catch (Exception e) {
            log.warn("Failed to extract database constraints for schema {}: {}", effectiveSchema, e.getMessage());
        }
    }
}
