package io.github.legacygraph.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChangeImpactQuestionParser} 单元测试 — 验证正则解析表名/字段名/类型与 UNKNOWN 降级。
 */
class ChangeImpactQuestionParserTest {

    private final ChangeImpactQuestionParser parser = new ChangeImpactQuestionParser();

    @Test
    void parse_addColumn_extractsTableAndColumn() {
        var r = parser.parse("给 lg_change_task 加 priority 字段怎么做");
        assertEquals("ADD_COLUMN", r.getChangeKind());
        assertEquals("lg_change_task", r.getTableName());
        assertEquals("priority", r.getColumnName());
    }

    @Test
    void parse_addColumnWithType_extractsType() {
        var r = parser.parse("给 lg_qa_message 加一个 status 字段 VARCHAR(32)");
        assertEquals("ADD_COLUMN", r.getChangeKind());
        assertEquals("lg_qa_message", r.getTableName());
        assertEquals("status", r.getColumnName());
        assertEquals("VARCHAR(32)", r.getColumnType());
    }

    @Test
    void parse_modifyColumn_detected() {
        var r = parser.parse("改 lg_change_task 表的 risk_level 字段长度");
        assertEquals("MODIFY_COLUMN", r.getChangeKind());
        assertEquals("lg_change_task", r.getTableName());
    }

    @Test
    void parse_refactor_detected() {
        var r = parser.parse("怎么重构这个系统");
        assertEquals("REFACTOR", r.getChangeKind());
    }

    @Test
    void parse_noTable_returnsUnknown() {
        var r = parser.parse("今天天气怎么样");
        assertEquals("UNKNOWN", r.getChangeKind());
        assertNull(r.getTableName());
    }

    @Test
    void parse_empty_returnsUnknown() {
        var r = parser.parse("");
        assertEquals("UNKNOWN", r.getChangeKind());
    }

    @Test
    void parse_null_returnsUnknown() {
        var r = parser.parse(null);
        assertEquals("UNKNOWN", r.getChangeKind());
    }

    @Test
    void parse_rawQuestionPreserved() {
        var q = "给 lg_test 加 col 字段";
        var r = parser.parse(q);
        assertEquals(q, r.getRawQuestion());
    }
}
