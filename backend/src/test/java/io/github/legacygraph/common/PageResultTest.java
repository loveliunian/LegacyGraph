package io.github.legacygraph.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PageResult} 单元测试 — 验证分页结果构造、totalPages 计算、边界条件。
 */
class PageResultTest {

    @Test
    void of_正常分页计算总页数() {
        PageResult<String> result = PageResult.of(
                List.of("a", "b", "c"),
                25L,
                1,
                10
        );

        assertEquals(3, result.getTotalPages(), "25条记录 / 每页10条 = 3页");
        assertEquals(25L, result.getTotal());
        assertEquals(1, result.getPageNum());
        assertEquals(10, result.getPageSize());
        assertEquals(3, result.getList().size());
    }

    @Test
    void of_总数刚好整除时总页数正确() {
        PageResult<String> result = PageResult.of(
                List.of("a", "b"),
                20L,
                2,
                10
        );

        assertEquals(2, result.getTotalPages(), "20条记录 / 每页10条 = 2页（刚好整除）");
        assertEquals(20L, result.getTotal());
        assertEquals(2, result.getPageNum());
    }

    @Test
    void of_空列表时总页数为0() {
        PageResult<String> result = PageResult.of(
                List.of(),
                0L,
                1,
                10
        );

        assertEquals(0, result.getTotalPages(), "0条记录时总页数应为0");
        assertTrue(result.getList().isEmpty());
    }

    @Test
    void of_总数小于页大小时总页数为1() {
        PageResult<String> result = PageResult.of(
                List.of("a", "b", "c"),
                3L,
                1,
                10
        );

        assertEquals(1, result.getTotalPages(), "3条记录 / 每页10条 = 1页");
    }

    @Test
    void of_单条记录时总页数为1() {
        PageResult<String> result = PageResult.of(
                List.of("a"),
                1L,
                1,
                20
        );

        assertEquals(1, result.getTotalPages());
        assertEquals(1L, result.getTotal());
    }

    @Test
    void of_largeTotal_正确计算大总数() {
        long total = 1_000_000L;
        int pageSize = 50;
        PageResult<String> result = PageResult.of(List.of(), total, 1, pageSize);

        assertEquals(20000, result.getTotalPages(), "100万条 / 每页50条 = 20000页");
    }

    @Test
    void noArgsConstructor_默认值正确() {
        PageResult<String> result = new PageResult<>();

        assertNull(result.getList());
        assertNull(result.getTotal());
        assertNull(result.getPageNum());
        assertNull(result.getPageSize());
        assertNull(result.getTotalPages());
    }

    @Test
    void allArgsConstructor_所有字段正确设置() {
        List<String> list = List.of("x", "y");
        PageResult<String> result = new PageResult<>(list, 100L, 5, 20, 5);

        assertEquals(list, result.getList());
        assertEquals(100L, result.getTotal());
        assertEquals(5, result.getPageNum());
        assertEquals(20, result.getPageSize());
        assertEquals(5, result.getTotalPages());
    }

    @Test
    void setters_字段可独立修改() {
        PageResult<String> result = new PageResult<>();
        result.setList(List.of("test"));
        result.setTotal(50L);
        result.setPageNum(3);
        result.setPageSize(15);
        result.setTotalPages(4);

        assertEquals(1, result.getList().size());
        assertEquals(50L, result.getTotal());
        assertEquals(3, result.getPageNum());
        assertEquals(15, result.getPageSize());
        assertEquals(4, result.getTotalPages());
    }

    @Test
    void of_边界情况_总数为1页多1条() {
        PageResult<String> result = PageResult.of(
                List.of("last"),
                101L,
                11,
                10
        );

        assertEquals(11, result.getTotalPages(), "101条 / 每页10条 = 11页（向上取整）");
    }
}
