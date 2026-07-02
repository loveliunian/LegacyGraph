package io.github.legacygraph.service;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.DatabaseMetadataExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

@Slf4j
@Service
public class DatabaseMetadataScanService {

    private final GraphBuilder graphBuilder;

    public DatabaseMetadataScanService(GraphBuilder graphBuilder) {
        this.graphBuilder = graphBuilder;
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
            log.info("Extracted {} tables from database schema {}", tables.size(), effectiveSchema);
            return tables.size();
        } catch (Exception e) {
            log.error("Failed to extract database metadata for schema {}", effectiveSchema, e);
            return 0;
        }
    }
}
