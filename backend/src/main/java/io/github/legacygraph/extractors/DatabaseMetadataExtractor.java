package io.github.legacygraph.extractors;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 数据库元数据抽取器
 * 从数据库information_schema抽取表、字段、主键、索引等信息
 */
@Slf4j
public class DatabaseMetadataExtractor {

    @Data
    public static class TableMetadata {
        private String tableCatalog;
        private String tableSchema;
        private String tableName;
        private String tableComment;
        private List<ColumnMetadata> columns = new ArrayList<>();
    }

    @Data
    public static class ColumnMetadata {
        private String columnName;
        private String dataType;
        private String typeName;
        private Integer columnSize;
        private Boolean nullable;
        private String columnDefault;
        private String columnComment;
        private Boolean primaryKey;
        private Boolean foreignKey;
        private String referencedTableName;
        private String referencedColumnName;
        // 语义识别
        private String semanticType; // id/status/type/deleted/created/updated
    }

    /**
     * 从指定schema抽取所有表元数据。
     * @param schema 对于 PostgreSQL 是 schema 名（如 public），对于 MySQL 传 null 即可
     */
    public List<TableMetadata> extractFromSchema(DataSource dataSource, String schema) throws SQLException {
        return extractFromSchema(dataSource, schema, false);
    }

    /**
     * 从指定schema抽取所有表元数据。
     * @param isMySql 是否为 MySQL/MariaDB（此时 schema 用作 catalog）
     */
    public List<TableMetadata> extractFromSchema(DataSource dataSource, String schema, boolean isMySql) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();

            // MySQL/MariaDB：catalog = database name，schema = null
            // PostgreSQL：catalog = null，schema = schema name
            String catalog = isMySql ? conn.getCatalog() : null;
            String schemaPattern = isMySql ? null : schema;

            // 获取所有表
            ResultSet tablesRs = metaData.getTables(
                    catalog,
                    schemaPattern,
                    "%",
                    new String[]{"TABLE"}
            );

            while (tablesRs.next()) {
                TableMetadata table = new TableMetadata();
                table.setTableCatalog(tablesRs.getString("TABLE_CAT"));
                table.setTableSchema(tablesRs.getString("TABLE_SCHEM"));
                table.setTableName(tablesRs.getString("TABLE_NAME"));
                // 获取表注释 - PostgreSQL特定
                table.setTableComment(getTableComment(conn, schema, table.getTableName()));

                // 抽取列
                List<ColumnMetadata> columns = extractColumns(metaData, conn, conn.getCatalog(), schema, table.getTableName());
                table.setColumns(columns);

                // 识别主键
                identifyPrimaryKeys(metaData, conn, conn.getCatalog(), schema, table.getTableName(), columns);

                // 语义识别
                semanticRecognize(columns);

                tables.add(table);
            }

            tablesRs.close();
        }

        return tables;
    }

    /**
     * 抽取列信息
     */
    private List<ColumnMetadata> extractColumns(DatabaseMetaData metaData, Connection conn,
            String catalog, String schema, String tableName) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();

        ResultSet columnsRs = metaData.getColumns(catalog, schema, tableName, "%");
        while (columnsRs.next()) {
            ColumnMetadata col = new ColumnMetadata();
            col.setColumnName(columnsRs.getString("COLUMN_NAME"));
            col.setDataType(columnsRs.getString("DATA_TYPE"));
            col.setTypeName(columnsRs.getString("TYPE_NAME"));
            col.setColumnSize(columnsRs.getInt("COLUMN_SIZE"));
            col.setNullable("YES".equals(columnsRs.getString("IS_NULLABLE")));
            col.setColumnDefault(columnsRs.getString("COLUMN_DEF"));
            // PostgreSQL特有注释获取
            col.setColumnComment(getColumnComment(conn, schema, tableName, col.getColumnName()));
            columns.add(col);
        }
        columnsRs.close();

        return columns;
    }

    /**
     * 识别主键
     */
    private void identifyPrimaryKeys(DatabaseMetaData metaData, Connection conn,
            String catalog, String schema, String tableName, List<ColumnMetadata> columns) throws SQLException {
        ResultSet pkRs = metaData.getPrimaryKeys(catalog, schema, tableName);
        while (pkRs.next()) {
            String pkColName = pkRs.getString("COLUMN_NAME");
            for (ColumnMetadata col : columns) {
                if (col.getColumnName().equals(pkColName)) {
                    col.setPrimaryKey(true);
                    break;
                }
            }
        }
        pkRs.close();
    }

    /**
     * PostgreSQL获取表注释
     */
    private String getTableComment(Connection conn, String schema, String tableName) {
        try {
            String sql = "SELECT obj_description(('\"' || ? || '\"'.'\"' || ? || '\"')::regclass)";
            java.sql.PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, schema);
            stmt.setString(2, tableName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString(1);
            }
            rs.close();
            stmt.close();
        } catch (Exception e) {
            log.debug("Failed to get table comment for {}.{}: {}", schema, tableName, e.getMessage());
        }
        return "";
    }

    /**
     * PostgreSQL获取列注释
     */
    private String getColumnComment(Connection conn, String schema, String tableName, String columnName) {
        try {
            String sql = "SELECT col_description(('\"' || ? || '\"'.'\"' || ? || '\"')::regclass, ordinal_position) " +
                         "FROM information_schema.columns " +
                         "WHERE table_schema = ? AND table_name = ? AND column_name = ?";
            java.sql.PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, schema);
            stmt.setString(2, tableName);
            stmt.setString(3, schema);
            stmt.setString(4, tableName);
            stmt.setString(5, columnName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString(1);
            }
            rs.close();
            stmt.close();
        } catch (Exception e) {
            log.debug("Failed to get column comment for {}.{}.{}: {}", schema, tableName, columnName, e.getMessage());
        }
        return "";
    }

    /**
     * 根据字段名和注释进行语义识别
     */
    private void semanticRecognize(List<ColumnMetadata> columns) {
        for (ColumnMetadata col : columns) {
            String colName = col.getColumnName().toLowerCase();
            String comment = col.getColumnComment() != null ? col.getColumnComment().toLowerCase() : "";

            if (colName.endsWith("_id") || colName.equals("id")) {
                col.setSemanticType("id");
            } else if (colName.contains("status") || colName.contains("state") ||
                      comment.contains("状态") || comment.contains("enum") || comment.contains("字典")) {
                col.setSemanticType("status");
            } else if (colName.contains("type") || colName.contains("category") ||
                       comment.contains("类型") || comment.contains("category")) {
                col.setSemanticType("type");
            } else if (colName.contains("delete") || colName.contains("del_flag") || colName.contains("is_deleted")) {
                col.setSemanticType("deleted");
            } else if (colName.contains("create") || colName.contains("update") ||
                       colName.endsWith("_at") || colName.endsWith("_time")) {
                col.setSemanticType("audit");
            }
            // 外键推断基于命名规则: xxx_id -> xxx.id
            if (colName.endsWith("_id") && !col.getColumnName().equals("id")) {
                col.setForeignKey(true);
                // 推断引用表名: user_id -> user
                String refTable = col.getColumnName().substring(0, col.getColumnName().length() - 3);
                col.setReferencedTableName(refTable);
                col.setReferencedColumnName("id");
            }
        }
    }
}
