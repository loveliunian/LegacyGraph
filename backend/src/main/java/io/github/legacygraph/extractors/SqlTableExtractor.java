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
import java.util.regex.Pattern;

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

    // MyBatis 占位符模式：#{xxx} 和 ${xxx}
    private static final Pattern MYBATIS_PARAM_PATTERN = Pattern.compile("#\\{[^}]*\\}");
    private static final Pattern MYBATIS_DYNAMIC_PATTERN = Pattern.compile("\\$\\{[^}]*\\}");

    /**
     * 解析SQL，提取表读写关系。
     * 对 MyBatis 动态 SQL 片段做预处理：清洗占位符，包装不完整片段后重试。
     * JSqlParser 失败时回退到正则提取。
     */
    public SqlTableResult extractTables(String sql) {
        SqlTableResult result = new SqlTableResult();

        if (sql == null || sql.isBlank()) {
            return result;
        }

        String cleaned = cleanMyBatisSql(sql);

        // 尝试直接解析
        Statement statement = tryParse(cleaned);
        if (statement == null) {
            // 尝试包装常见片段后重试
            String wrapped = wrapFragment(cleaned);
            if (wrapped != null) {
                statement = tryParse(wrapped);
            }
        }

        if (statement == null) {
            // JSqlParser 失败，回退到正则提取
            log.debug("JSqlParser failed, using regex fallback (first 100 chars): {}", 
                    sql.substring(0, Math.min(sql.length(), 100)).replace('\n', ' '));
            return extractTablesWithRegex(cleaned);
        }

        classifyStatement(statement, result);
        return result;
    }

    /**
     * 清洗 MyBatis 动态 SQL：
     * 1. #{xxx} → ?（JDBC 参数占位符，JSqlParser 原生支持）
     * 2. ${xxx} → 'dyn_val'（动态字符串替换）
     */
    private String cleanMyBatisSql(String sql) {
        String cleaned = MYBATIS_PARAM_PATTERN.matcher(sql).replaceAll("?");
        cleaned = MYBATIS_DYNAMIC_PATTERN.matcher(cleaned).replaceAll("'dyn_val'");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    /**
     * 尝试用 JSqlParser 解析，失败返回 null。
     */
    private Statement tryParse(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 对 SQL 片段做包装，使其成为合法的完整语句：
     * - 以 WHERE/AND/OR 开头 → SELECT * FROM __DUAL__ <fragment>
     * - 以 SET 开头 → UPDATE __DUAL__ <fragment>
     * - 其他碎片不包装，返回 null
     */
    private String wrapFragment(String sql) {
        String upper = sql.toUpperCase().trim();
        if (upper.startsWith("WHERE ") || upper.startsWith("AND ") || upper.startsWith("OR ")) {
            return "SELECT * FROM __DUAL__ " + sql;
        }
        if (upper.startsWith("SET ")) {
            return "UPDATE __DUAL__ " + sql;
        }
        // 以 ORDER BY / GROUP BY / LIMIT 等开头也尝试包装
        if (upper.startsWith("ORDER ") || upper.startsWith("GROUP ") || upper.startsWith("LIMIT ")
                || upper.startsWith("HAVING ")) {
            return "SELECT * FROM __DUAL__ " + sql;
        }
        return null;
    }

    /**
     * 根据语句类型分发到对应的表提取方法。
     */
    private void classifyStatement(Statement statement, SqlTableResult result) {
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

    /**
     * 正则回退提取：当 JSqlParser 无法解析时使用
     */
    private SqlTableResult extractTablesWithRegex(String sql) {
        SqlTableResult result = new SqlTableResult();
        String upper = sql.toUpperCase();

        // 判断SQL类型
        if (upper.startsWith("SELECT")) {
            result.setSqlType("SELECT");
        } else if (upper.startsWith("INSERT")) {
            result.setSqlType("INSERT");
        } else if (upper.startsWith("UPDATE")) {
            result.setSqlType("UPDATE");
        } else if (upper.startsWith("DELETE")) {
            result.setSqlType("DELETE");
        }

        // 提取 FROM 后的表名
        Pattern fromPattern = Pattern.compile("\\bFROM\\s+([a-zA-Z_][a-zA-Z0-9_.]+)", Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher fromMatcher = fromPattern.matcher(sql);
        while (fromMatcher.find()) {
            String tableName = fromMatcher.group(1);
            if (!isReservedWord(tableName)) {
                result.getReadTables().add(tableName);
            }
        }

        // 提取 JOIN 后的表名
        Pattern joinPattern = Pattern.compile("\\bJOIN\\s+([a-zA-Z_][a-zA-Z0-9_.]+)", Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher joinMatcher = joinPattern.matcher(sql);
        while (joinMatcher.find()) {
            String tableName = joinMatcher.group(1);
            if (!isReservedWord(tableName)) {
                result.getJoinTables().add(tableName);
                result.getReadTables().add(tableName); // JOIN 表也是读表
            }
        }

        // 提取 INSERT INTO 后的表名
        Pattern insertPattern = Pattern.compile("\\bINSERT\\s+INTO\\s+([a-zA-Z_][a-zA-Z0-9_.]+)", Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher insertMatcher = insertPattern.matcher(sql);
        while (insertMatcher.find()) {
            String tableName = insertMatcher.group(1);
            if (!isReservedWord(tableName)) {
                result.getWriteTables().add(tableName);
            }
        }

        // 提取 UPDATE 后的表名
        Pattern updatePattern = Pattern.compile("\\bUPDATE\\s+([a-zA-Z_][a-zA-Z0-9_.]+)", Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher updateMatcher = updatePattern.matcher(sql);
        while (updateMatcher.find()) {
            String tableName = updateMatcher.group(1);
            if (!isReservedWord(tableName)) {
                result.getWriteTables().add(tableName);
            }
        }

        // 提取 DELETE FROM 后的表名
        Pattern deletePattern = Pattern.compile("\\bDELETE\\s+FROM\\s+([a-zA-Z_][a-zA-Z0-9_.]+)", Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher deleteMatcher = deletePattern.matcher(sql);
        while (deleteMatcher.find()) {
            String tableName = deleteMatcher.group(1);
            if (!isReservedWord(tableName)) {
                result.getWriteTables().add(tableName);
            }
        }

        return result;
    }

    /**
     * 判断是否为SQL保留词
     */
    private boolean isReservedWord(String word) {
        Set<String> reserved = Set.of(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "EXISTS",
            "JOIN", "INNER", "LEFT", "RIGHT", "OUTER", "ON", "AS", "SET",
            "INSERT", "INTO", "VALUES", "UPDATE", "DELETE", "CREATE", "DROP",
            "ALTER", "TABLE", "INDEX", "VIEW", "ORDER", "BY", "GROUP", "HAVING",
            "LIMIT", "OFFSET", "UNION", "ALL", "DISTINCT", "NULL", "TRUE", "FALSE"
        );
        return reserved.contains(word.toUpperCase());
    }
}
