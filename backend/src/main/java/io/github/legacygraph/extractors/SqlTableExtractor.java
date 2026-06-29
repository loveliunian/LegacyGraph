package io.github.legacygraph.extractors;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SQL表关系抽取器
 * 使用JSqlParser解析SQL，抽取读写表和JOIN表关系
 */
@Slf4j
public class SqlTableExtractor {

    @Data
    public static class SqlTableResult {
        private Set<String> readTables = new HashSet<>();
        private Set<String> writeTables = new HashSet<>();
        private Set<String> joinTables = new HashSet<>();
        private String sqlType;
    }

    /**
     * 解析SQL，提取表读写关系
     */
    public SqlTableResult extractTables(String sql) {
        SqlTableResult result = new SqlTableResult();

        try {
            Statement statement = CCJSqlParserUtil.parse(sql);

            if (statement instanceof Select) {
                result.setSqlType("SELECT");
                extractSelectTables((Select) statement, result);
            } else if (statement instanceof Insert) {
                result.setSqlType("INSERT");
                extractInsertTables((Insert) statement, result);
            } else if (statement instanceof Update) {
                result.setSqlType("UPDATE");
                extractUpdateTables((Update) statement, result);
            } else if (statement instanceof Delete) {
                result.setSqlType("DELETE");
                extractDeleteTables((Delete) statement, result);
            }

        } catch (Exception e) {
            log.warn("Failed to parse SQL: {}", e.getMessage());
            // 解析失败不抛出，继续返回空结果
        }

        return result;
    }

    /**
     * 处理SELECT语句
     */
    private void extractSelectTables(Select select, SqlTableResult result) {
        findTablesInFrom(select, result);
    }

    /**
     * 处理INSERT语句
     */
    private void extractInsertTables(Insert insert, SqlTableResult result) {
        Table table = insert.getTable();
        if (table != null) {
            result.getWriteTables().add(getTableName(table));
        }

        // INSERT ... SELECT 需要处理SELECT中的读表
        if (insert.getSelect() != null) {
            extractSelectTables(insert.getSelect(), result);
        }
    }

    /**
     * 处理UPDATE语句
     */
    private void extractUpdateTables(Update update, SqlTableResult result) {
        // UPDATE表本身是写入
        if (update.getTable() != null) {
            result.getWriteTables().add(getTableName(update.getTable()));
        }

        // WHERE条件中可能有JOIN读表
        if (update.getWhere() != null) {
            // JSqlParser会处理，这里需要提取JOIN表
            // 简化处理，所有表都算作读
        }
    }

    /**
     * 处理DELETE语句
     */
    private void extractDeleteTables(Delete delete, SqlTableResult result) {
        Table table = delete.getTable();
        if (table != null) {
            result.getWriteTables().add(getTableName(table));
        }
    }

    /**
     * 从FROM子句查找所有表
     */
    private void findTablesInFrom(Object fromItem, SqlTableResult result) {
        if (fromItem instanceof net.sf.jsqlparser.statement.select.Values) {
            // VALUES 子句（如 INSERT INTO t VALUES(...)）没有表引用，跳过
            return;
        } else if (fromItem instanceof Table) {
            Table table = (Table) fromItem;
            result.getReadTables().add(getTableName(table));
        } else if (fromItem instanceof PlainSelect) {
            PlainSelect plainSelect = (PlainSelect) fromItem;
            findTablesInFrom(plainSelect.getFromItem(), result);
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    findTablesInFrom(join.getRightItem(), result);
                    if (join.getRightItem() instanceof Table) {
                        result.getJoinTables().add(getTableName((Table) join.getRightItem()));
                    }
                }
            }
        } else if (fromItem instanceof ParenthesedSelect) {
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) fromItem;
            findTablesInFrom(parenthesedSelect.getSelect(), result);
        } else if (fromItem instanceof Select) {
            Select select = (Select) fromItem;
            findTablesInFrom(select.getSelectBody(), result);
        }
        // 其余 FromItem 类型（TableFunction、LateralSubSelect 等）不含可静态解析的实体表，
        // 故不纳入读/写表统计；如需覆盖可在此扩展对应分支。
    }

    /**
     * 获取表名（去掉schema）
     */
    private String getTableName(Table table) {
        String name = table.getName();
        if (table.getSchemaName() != null) {
            // 保留schema.table格式用于nodeKey
            return table.getSchemaName() + "." + name;
        }
        return name;
    }

    /**
     * 根据SQL类型判断关系类型
     */
    public static String classifyRelationType(String sqlType) {
        switch (sqlType.toUpperCase()) {
            case "SELECT":
                return "READS";
            case "INSERT":
            case "DELETE":
                return "WRITES";
            case "UPDATE":
                return "WRITES"; // UPDATE 既是读又是写
            default:
                return "READS";
        }
    }
}
