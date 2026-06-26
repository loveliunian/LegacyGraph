package io.github.legacygraph.extractors;

import io.github.legacygraph.model.MapperSqlFact;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlFile);
            doc.getDocumentElement().normalize();

            // 获取namespace
            String namespace = doc.getDocumentElement().getAttribute("namespace");
            fact.setNamespace(namespace);

            // 处理各个statement元素
            List<SqlStatement> statements = new ArrayList<>();
            statements.addAll(extractStatements(doc, "select"));
            statements.addAll(extractStatements(doc, "insert"));
            statements.addAll(extractStatements(doc, "update"));
            statements.addAll(extractStatements(doc, "delete"));

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
     * 提取指定类型的SQL语句
     */
    private List<SqlStatement> extractStatements(Document doc, String tagName) {
        List<SqlStatement> result = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName(tagName);

        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            SqlStatement stmt = new SqlStatement();
            stmt.setId(element.getAttribute("id"));
            stmt.setType(tagName);
            stmt.setSql(element.getTextContent().trim());
            // TODO: 展开include片段
            // TODO: 行号解析需要SAX parser，这里简化处理
            result.add(stmt);
        }

        return result;
    }
}
