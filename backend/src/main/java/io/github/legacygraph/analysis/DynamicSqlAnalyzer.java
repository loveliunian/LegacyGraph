package io.github.legacygraph.analysis;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 动态 SQL 分析器 — 解析 MyBatis XML 中的动态元素。
 * 覆盖 &lt;if&gt;/&lt;choose&gt;/&lt;foreach&gt;/&lt;where&gt; 等运行时拼接节点，
 * 抽取结果用于在图谱中标注 SQL 语句的动态分支，辅助影响范围分析。
 */
@Slf4j
@Component
public class DynamicSqlAnalyzer {

    private static final List<String> DYNAMIC_TAGS = List.of("if", "choose", "foreach", "where");

    /**
     * 从 MyBatis XML 文件抽取动态 SQL 事实。
     */
    public List<DynamicSqlFact> extractFromFile(Path xmlFile) {
        List<DynamicSqlFact> result = new ArrayList<>();
        if (!Files.exists(xmlFile) || !Files.isReadable(xmlFile)) {
            return result;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 与 MyBatisXmlExtractor 一致：禁用外部 DTD/实体加载，防止离线阻塞与 XXE
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlFile.toFile());
            doc.getDocumentElement().normalize();

            String sourcePath = xmlFile.toString();

            // 遍历所有 SQL 语句节点（select/insert/update/delete），收集其 id 作为 sqlId 上下文
            for (String statementTag : List.of("select", "insert", "update", "delete")) {
                NodeList statements = doc.getElementsByTagName(statementTag);
                for (int i = 0; i < statements.getLength(); i++) {
                    Element statement = (Element) statements.item(i);
                    String sqlId = statement.getAttribute("id");
                    if (sqlId == null || sqlId.isEmpty()) {
                        continue;
                    }
                    // 递归收集该语句下的所有动态元素
                    collectDynamicElements(statement, sqlId, sourcePath, result);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse MyBatis XML for dynamic SQL: {}", xmlFile, e);
        }

        return result;
    }

    /**
     * 递归遍历语句节点下的所有动态元素（if/choose/foreach/where），含嵌套。
     */
    private void collectDynamicElements(Element parent, String sqlId,
                                         String sourcePath, List<DynamicSqlFact> result) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) node;
            String tagName = el.getNodeName();
            if (!DYNAMIC_TAGS.contains(tagName)) {
                continue;
            }

            DynamicSqlFact fact = new DynamicSqlFact();
            fact.setSqlId(sqlId);
            fact.setDynamicElementType(tagName.toUpperCase());
            fact.setSourcePath(sourcePath);
            // <if test="..."> 的 test 属性即为条件；<foreach> 的 collection/item/index 亦可作为条件描述
            switch (tagName) {
                case "if" -> fact.setCondition(el.getAttribute("test"));
                case "foreach" -> fact.setCondition(buildForeachCondition(el));
                // choose/where 本身无单一条件表达式，condition 留空
                default -> fact.setCondition(null);
            }
            result.add(fact);

            // 递归处理嵌套动态元素（如 <choose> 中的 <when><if>）
            collectDynamicElements(el, sqlId, sourcePath, result);
        }
    }

    /**
     * 拼接 <foreach> 的关键属性，作为条件描述。
     */
    private String buildForeachCondition(Element foreach) {
        StringBuilder sb = new StringBuilder();
        appendAttr(sb, "collection", foreach.getAttribute("collection"));
        appendAttr(sb, "item", foreach.getAttribute("item"));
        appendAttr(sb, "index", foreach.getAttribute("index"));
        appendAttr(sb, "open", foreach.getAttribute("open"));
        appendAttr(sb, "close", foreach.getAttribute("close"));
        appendAttr(sb, "separator", foreach.getAttribute("separator"));
        return sb.length() == 0 ? null : sb.toString();
    }

    private void appendAttr(StringBuilder sb, String name, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(name).append("=").append(value);
    }

    /**
     * 动态 SQL 事实。
     */
    @Data
    public static class DynamicSqlFact {
        /** 所属 SQL 语句的 id（mapper 方法名） */
        private String sqlId;
        /** 动态元素类型：IF/CHOOSE/FOREACH/WHERE */
        private String dynamicElementType;
        /** 条件表达式：<if test="..."> 或 <foreach> 属性拼接；choose/where 为 null */
        private String condition;
        /** 源文件路径 */
        private String sourcePath;
    }
}
