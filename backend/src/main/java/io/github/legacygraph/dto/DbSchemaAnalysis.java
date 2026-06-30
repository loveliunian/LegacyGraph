package io.github.legacygraph.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据库 Schema LLM 分析结果。
 * 从原始表结构元数据中识别业务含义、隐式关系和业务域。
 */
@Data
public class DbSchemaAnalysis {

    /** 分析的表列表 */
    private List<TableInsight> tables = new ArrayList<>();

    /** 识别出的业务域 */
    private List<BusinessDomain> domains = new ArrayList<>();

    /** 识别出的跨表关系（命名规则无法推断的） */
    private List<ImplicitRelation> implicitRelations = new ArrayList<>();

    /** 整体 Schema 级别摘要 */
    private String schemaSummary;

    @Data
    public static class TableInsight {
        /** 表名（与输入对应） */
        private String tableName;
        /** LLM 推断的业务中文名 */
        private String businessLabel;
        /** LLM 推断的业务描述 */
        private String businessDescription;
        /** LLM 推断的业务域 */
        private String domain;
        /** 重要程度：CORE / SUPPORT / LOOKUP */
        private String importance;
    }

    @Data
    public static class BusinessDomain {
        private String name;
        private String description;
        /** 属于该域的表名列表 */
        private List<String> tables = new ArrayList<>();
    }

    @Data
    public static class ImplicitRelation {
        /** 源表 */
        private String fromTable;
        /** 目标表 */
        private String toTable;
        /** 关系类型，如 ONE_TO_MANY / MANY_TO_MANY / REFERENCE */
        private String relationType;
        /** 关系说明 */
        private String description;
    }
}
