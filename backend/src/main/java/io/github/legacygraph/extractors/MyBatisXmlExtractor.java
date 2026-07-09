package io.github.legacygraph.extractors;

import io.github.legacygraph.model.MapperSqlFact;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MyBatis XML Mapper 抽取器
 * 解析MyBatis XML映射文件，抽取SQL语句和表关系
 */
@Slf4j
public class MyBatisXmlExtractor {

    /**
     * SQL语句事实
     */
    @Data
    public static class SqlStatement {
        private String id;
        private String type; // select/insert/update/delete
        private String sql;
        private String expandedSql; // 展开include后的SQL
        private int startLine;
        private int endLine;
        private List<String> readTables = new ArrayList<>();
        private List<String> writeTables = new ArrayList<>();
        private List<String> joinTables = new ArrayList<>();
    }

    /**
     * 从XML文件抽取Mapper信息
     */
    public MapperSqlFact extractFromFile(File xmlFile) {
        MapperSqlFact fact = new MapperSqlFact();
        fact.setSourcePath(xmlFile.getAbsolutePath());

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 禁用外部 DTD/实体加载：MyBatis Mapper 的 DOCTYPE 指向 http://mybatis.org/dtd/...，
            // 在离线或网络受限环境下会导致解析阻塞甚至失败。同时可防止 XXE 注入。
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlFile);
            doc.getDocumentElement().normalize();

            // 读取原始文本用于近似行号解析（DOM 不保留位置信息）
            String rawText = java.nio.file.Files.readString(xmlFile.toPath());

            // 获取namespace
            String namespace = doc.getDocumentElement().getAttribute("namespace");
            fact.setNamespace(namespace);

            // 收集可复用的 <sql id="..."> 片段，供 <include refid> 展开
            Map<String, String> sqlFragments = collectSqlFragments(doc);

            // 处理各个statement元素
            List<SqlStatement> statements = new ArrayList<>();
            statements.addAll(extractStatements(doc, "select", sqlFragments, rawText));
            statements.addAll(extractStatements(doc, "insert", sqlFragments, rawText));
            statements.addAll(extractStatements(doc, "update", sqlFragments, rawText));
            statements.addAll(extractStatements(doc, "delete", sqlFragments, rawText));

            fact.setStatements(statements);

            // 收集所有涉及的表
            List<String> allReadTables = new ArrayList<>();
            List<String> allWriteTables = new ArrayList<>();
            for (SqlStatement stmt : statements) {
                allReadTables.addAll(stmt.getReadTables());
                allWriteTables.addAll(stmt.getWriteTables());
            }
            fact.setAllReadTables(allReadTables);
            fact.setAllWriteTables(allWriteTables);

        } catch (Exception e) {
            log.error("Failed to parse MyBatis XML: {}", xmlFile, e);
        }

        return fact;
    }

    /**
     * 收集所有 <sql id="..."> 片段，key 为 refid，value 为片段文本（已展开嵌套 include）
     */
    private Map<String, String> collectSqlFragments(Document doc) {
        Map<String, String> fragments = new HashMap<>();
        NodeList sqlNodes = doc.getElementsByTagName("sql");
        for (int i = 0; i < sqlNodes.getLength(); i++) {
            Element el = (Element) sqlNodes.item(i);
            String id = el.getAttribute("id");
            if (id != null && !id.isEmpty()) {
                fragments.put(id, el.getTextContent().trim());
            }
        }
        return fragments;
    }

    /**
     * 提取指定类型的SQL语句，并展开 <include refid> 片段
     */
    private List<SqlStatement> extractStatements(Document doc, String tagName,
                                                 Map<String, String> sqlFragments, String rawText) {
        List<SqlStatement> result = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName(tagName);

        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            SqlStatement stmt = new SqlStatement();
            String id = element.getAttribute("id");
            stmt.setId(id);
            stmt.setType(tagName);
            String rawSql = element.getTextContent().trim();
            stmt.setSql(rawSql);
            // 展开 include 片段后的 SQL（无 include 时与原 SQL 一致）
            stmt.setExpandedSql(expandIncludes(element, sqlFragments));
            // 近似行号：在原始文本中定位 <tag ... id="theId"> 及其闭合标签
            resolveLineNumbers(stmt, tagName, id, rawText);
            // 解析 SQL 语句涉及的表名
            resolveTables(stmt, stmt.getExpandedSql());
            result.add(stmt);
        }

        return result;
    }

    /**
     * 解析 SQL 语句涉及的表名，按语句类型填充 readTables / writeTables / joinTables。
     * <p>
     * 近似解析（不依赖完整 SQL 词法器），覆盖常见 MyBatis 场景：
     * <ul>
     *   <li>SELECT ... FROM t1 JOIN t2 → readTables=[t1], joinTables=[t2]</li>
     *   <li>INSERT INTO t1 → writeTables=[t1]</li>
     *   <li>UPDATE t1 → writeTables=[t1]</li>
     *   <li>DELETE FROM t1 → writeTables=[t1]（删除语义上属写操作）</li>
     * </ul>
     * </p>
     * 表名去 schema 前缀、去别名、去子查询括号；忽略子查询内的表名（近似）。
     */
    private void resolveTables(SqlStatement stmt, String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        // 移除 MyBatis #{} / ${} 占位符，避免干扰关键字识别
        String normalized = sql.replaceAll("#\\{[^}]*}", "?")
                .replaceAll("\\$\\{[^}]*}", "?");
        String type = stmt.getType() == null ? "" : stmt.getType().toLowerCase();

        switch (type) {
            case "select":
                resolveSelectTables(stmt, normalized);
                break;
            case "insert":
                resolveInsertTables(stmt, normalized);
                break;
            case "update":
                resolveUpdateTables(stmt, normalized);
                break;
            case "delete":
                resolveDeleteTables(stmt, normalized);
                break;
            default:
                break;
        }
    }

    /** SELECT ... FROM t1 [JOIN|INNER JOIN|LEFT JOIN|RIGHT JOIN|FULL JOIN t2 ...] */
    private void resolveSelectTables(SqlStatement stmt, String sql) {
        java.util.regex.Matcher fromMatcher = FROM_PATTERN.matcher(sql);
        if (fromMatcher.find()) {
            String firstTable = extractTableName(fromMatcher.group(1));
            if (firstTable != null) {
                stmt.getReadTables().add(firstTable);
            }
        }
        java.util.regex.Matcher joinMatcher = JOIN_PATTERN.matcher(sql);
        while (joinMatcher.find()) {
            String joinTable = extractTableName(joinMatcher.group(2));
            if (joinTable != null) {
                stmt.getJoinTables().add(joinTable);
            }
        }
    }

    /** INSERT INTO t1 ... / INSERT OR REPLACE INTO t1 ... */
    private void resolveInsertTables(SqlStatement stmt, String sql) {
        java.util.regex.Matcher m = INSERT_INTO_PATTERN.matcher(sql);
        if (m.find()) {
            String table = extractTableName(m.group(1));
            if (table != null) {
                stmt.getWriteTables().add(table);
            }
        }
    }

    /** UPDATE t1 SET ... */
    private void resolveUpdateTables(SqlStatement stmt, String sql) {
        java.util.regex.Matcher m = UPDATE_PATTERN.matcher(sql);
        if (m.find()) {
            String table = extractTableName(m.group(1));
            if (table != null) {
                stmt.getWriteTables().add(table);
            }
        }
    }

    /** DELETE FROM t1 ... */
    private void resolveDeleteTables(SqlStatement stmt, String sql) {
        java.util.regex.Matcher m = DELETE_FROM_PATTERN.matcher(sql);
        if (m.find()) {
            String table = extractTableName(m.group(1));
            if (table != null) {
                stmt.getWriteTables().add(table);
            }
        }
    }

    /**
     * 从 FROM/JOIN/INTO/UPDATE 子句捕获组中提取表名。
     * 输入形如 "t_user" / "t_user u" / "schema.t_user AS u" / "(subquery) alias"。
     * 子查询（以 "(" 开头）返回 null（近似忽略）。
     */
    private String extractTableName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty() || s.startsWith("(")) {
            return null;
        }
        // 取第一个 token（表名或 schema.table），去除逗号/分号
        s = s.split("[\\s,;]")[0];
        // 去 schema 前缀，保留最后一段
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) {
            s = s.substring(dot + 1);
        }
        // 去可能的反引号/双引号包裹
        s = s.replaceAll("[`\"`]", "");
        return s.isEmpty() ? null : s;
    }

    // SQL 关键字 + 表名捕获模式（大小写不敏感，multiline 以适配多行 SQL）
    private static final java.util.regex.Pattern FROM_PATTERN =
            java.util.regex.Pattern.compile("\\bFROM\\s+([^\\s()]+(?:\\s+(?:AS\\s+)?[^\\s()]+)?)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern JOIN_PATTERN =
            java.util.regex.Pattern.compile("\\b(INNER\\s+|LEFT\\s+(?:OUTER\\s+)?|RIGHT\\s+(?:OUTER\\s+)?|FULL\\s+(?:OUTER\\s+)?)?JOIN\\s+([^\\s()]+(?:\\s+(?:AS\\s+)?[^\\s()]+)?)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern INSERT_INTO_PATTERN =
            java.util.regex.Pattern.compile("\\bINSERT\\s+(?:OR\\s+REPLACE\\s+)?INTO\\s+([^\\s()]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern UPDATE_PATTERN =
            java.util.regex.Pattern.compile("\\bUPDATE\\s+([^\\s()]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern DELETE_FROM_PATTERN =
            java.util.regex.Pattern.compile("\\bDELETE\\s+FROM\\s+([^\\s()]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 在原始 XML 文本中近似定位语句的起止行号。
     * 以 &lt;tag ... id="theId" 作为起点，&lt;/tag&gt; 作为终点。
     */
    private void resolveLineNumbers(SqlStatement stmt, String tagName, String id, String rawText) {
        if (rawText == null || id == null || id.isEmpty()) {
            return;
        }
        // 匹配形如 <select ... id="theId"
        java.util.regex.Pattern openPat = java.util.regex.Pattern.compile(
                "<" + java.util.regex.Pattern.quote(tagName) + "\\b[^>]*\\bid\\s*=\\s*[\"']"
                        + java.util.regex.Pattern.quote(id) + "[\"']");
        java.util.regex.Matcher m = openPat.matcher(rawText);
        if (!m.find()) {
            return;
        }
        int startOffset = m.start();
        int startLine = countLines(rawText, startOffset);
        stmt.setStartLine(startLine);

        // 从开标签之后查找对应的闭合标签
        int closeIdx = rawText.indexOf("</" + tagName + ">", m.end());
        if (closeIdx >= 0) {
            stmt.setEndLine(countLines(rawText, closeIdx));
        } else {
            stmt.setEndLine(startLine);
        }
    }

    /** 计算 offset 处的行号（从 1 开始） */
    private int countLines(String text, int offset) {
        int line = 1;
        int limit = Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * 展开语句内的 &lt;include refid="..."/&gt; 片段。
     * 遍历子节点，遇到 include 元素时替换为对应 &lt;sql&gt; 片段文本，其余文本原样保留。
     */
    private String expandIncludes(Element statement, Map<String, String> sqlFragments) {
        StringBuilder sb = new StringBuilder();
        NodeList children = statement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "include".equals(child.getNodeName())) {
                String refid = ((Element) child).getAttribute("refid");
                String fragment = sqlFragments.get(refid);
                if (fragment != null) {
                    sb.append(' ').append(fragment).append(' ');
                } else {
                    log.debug("Unresolved <include refid=\"{}\"> in statement {}", refid, statement.getAttribute("id"));
                }
            } else {
                sb.append(child.getTextContent());
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }
}
