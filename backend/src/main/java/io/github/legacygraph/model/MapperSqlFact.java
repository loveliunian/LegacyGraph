package io.github.legacygraph.model;

import io.github.legacygraph.extractors.MyBatisXmlExtractor;
import lombok.Data;

import java.util.List;

/**
 * MyBatis Mapper 抽取结果事实
 */
@Data
public class MapperSqlFact {

    private String namespace;
    private String mapperInterface;
    private String sourcePath;
    private List<MyBatisXmlExtractor.SqlStatement> statements;
    private List<String> allReadTables;
    private List<String> allWriteTables;

    /** 事实来源类型（MYBATIS_XML / MYBATIS_ANNOTATION），默认 MYBATIS_XML 保持向后兼容 */
    private String sourceType = "MYBATIS_XML";
}
