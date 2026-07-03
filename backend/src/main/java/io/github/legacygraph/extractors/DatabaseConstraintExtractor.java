package io.github.legacygraph.extractors;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库约束抽取器 — 在 DatabaseMetadataExtractor 之外补充外键、索引、唯一约束抽取。
 * 配合 DatabaseMetadataScanService 使用。
 */
@Slf4j
@Component
public class DatabaseConstraintExtractor {

    @Data
    public static class ForeignKeyInfo {
        private String fkName;
        private String pkTableName;
        private String pkColumnName;
        private String fkTableName;
        private String fkColumnName;
        private short updateRule;
        private short deleteRule;
    }

    @Data
    public static class IndexInfo {
        private String indexName;
        private String tableName;
        private List<String> columnNames;
        private boolean nonUnique;
        private boolean isUnique;
    }

    /**
     * 抽取外键约束。
     */
    public List<ForeignKeyInfo> extractForeignKeys(DataSource dataSource, String catalog, String schema) throws SQLException {
        List<ForeignKeyInfo> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            // 遍历所有表
            try (ResultSet tables = meta.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    try (ResultSet fks = meta.getImportedKeys(catalog, schema, tableName)) {
                        while (fks.next()) {
                            ForeignKeyInfo fk = new ForeignKeyInfo();
                            fk.setFkName(fks.getString("FK_NAME"));
                            fk.setPkTableName(fks.getString("PKTABLE_NAME"));
                            fk.setPkColumnName(fks.getString("PKCOLUMN_NAME"));
                            fk.setFkTableName(fks.getString("FKTABLE_NAME"));
                            fk.setFkColumnName(fks.getString("FKCOLUMN_NAME"));
                            fk.setUpdateRule(fks.getShort("UPDATE_RULE"));
                            fk.setDeleteRule(fks.getShort("DELETE_RULE"));
                            result.add(fk);
                        }
                    }
                }
            }
        }
        log.info("Extracted {} foreign key constraints", result.size());
        return result;
    }

    /**
     * 抽取索引信息（含唯一约束）。
     */
    public List<IndexInfo> extractIndexes(DataSource dataSource, String catalog, String schema) throws SQLException {
        List<IndexInfo> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet tables = meta.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    try (ResultSet indexes = meta.getIndexInfo(catalog, schema, tableName, false, true)) {
                        // 按 indexName 聚合，getIndexInfo 对复合索引每列返回一行
                        Map<String, IndexInfo> indexMap = new LinkedHashMap<>();
                        while (indexes.next()) {
                            String indexName = indexes.getString("INDEX_NAME");
                            if (indexName == null) continue;

                            IndexInfo idx = indexMap.computeIfAbsent(indexName, k -> {
                                IndexInfo info = new IndexInfo();
                                info.setIndexName(k);
                                info.setTableName(tableName);
                                info.setColumnNames(new ArrayList<>());
                                return info;
                            });
                            idx.getColumnNames().add(indexes.getString("COLUMN_NAME"));
                            // NON_UNIQUE 在每行结果中一致，取第一行即可
                            if (idx.getColumnNames().size() == 1) {
                                idx.setNonUnique(indexes.getBoolean("NON_UNIQUE"));
                                idx.setUnique(!idx.isNonUnique());
                            }
                        }
                        result.addAll(indexMap.values());
                    }
                }
            }
        }
        log.info("Extracted {} index entries", result.size());
        return result;
    }
}
