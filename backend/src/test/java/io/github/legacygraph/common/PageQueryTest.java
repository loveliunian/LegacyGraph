package io.github.legacygraph.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PageQuery} 单元测试 — 验证分页查询参数默认值、字段设置。
 */
class PageQueryTest {

    @Test
    void defaultValues_默认页码和页大小正确() {
        PageQuery query = new PageQuery();

        assertEquals(1, query.getPageNum(), "默认页码应为1");
        assertEquals(20, query.getPageSize(), "默认页大小应为20");
        assertNull(query.getKeyword(), "默认 keyword 应为 null");
    }

    @Test
    void setPageNum_可自定义页码() {
        PageQuery query = new PageQuery();
        query.setPageNum(5);

        assertEquals(5, query.getPageNum());
    }

    @Test
    void setPageSize_可自定义页大小() {
        PageQuery query = new PageQuery();
        query.setPageSize(50);

        assertEquals(50, query.getPageSize());
    }

    @Test
    void setKeyword_可设置搜索关键词() {
        PageQuery query = new PageQuery();
        query.setKeyword("test");

        assertEquals("test", query.getKeyword());
    }

    @Test
    void allFields_所有字段可同时设置() {
        PageQuery query = new PageQuery();
        query.setPageNum(3);
        query.setPageSize(100);
        query.setKeyword("order");

        assertEquals(3, query.getPageNum());
        assertEquals(100, query.getPageSize());
        assertEquals("order", query.getKeyword());
    }

    @Test
    void keyword_nullIsValid() {
        PageQuery query = new PageQuery();
        query.setKeyword(null);

        assertNull(query.getKeyword());
    }

    @Test
    void keyword_emptyStringIsValid() {
        PageQuery query = new PageQuery();
        query.setKeyword("");

        assertEquals("", query.getKeyword());
    }
}
