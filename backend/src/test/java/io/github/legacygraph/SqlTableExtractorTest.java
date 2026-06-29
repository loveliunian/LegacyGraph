package io.github.legacygraph;

import io.github.legacygraph.extractors.SqlTableExtractor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlTableExtractor 单元测试
 * 测试 SQL 表关系抽取逻辑：读表、写表、JOIN 表
 */
class SqlTableExtractorTest {

    private final SqlTableExtractor extractor = new SqlTableExtractor();

    @Test
    void testSelectExtractsReadTables() {
        // SELECT t_user — 应识别为 SELECT 类型，t_user 为读表
        SqlTableExtractor.SqlTableResult result = extractor.extractTables("SELECT * FROM t_user");

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertTrue(result.getReadTables().contains("t_user"), "t_user 应为读表");
        assertTrue(result.getWriteTables().isEmpty(), "SELECT 不应有写表");
        assertTrue(result.getJoinTables().isEmpty(), "SELECT 不应有 JOIN 表");
    }

    @Test
    void testInsertExtractsWriteAndReadTables() {
        // INSERT t_order — t_order 为写表
        SqlTableExtractor.SqlTableResult result = extractor.extractTables("INSERT INTO t_order (id, name) VALUES (1, 'test')");

        assertNotNull(result);
        assertEquals("INSERT", result.getSqlType());
        assertTrue(result.getWriteTables().contains("t_order"), "t_order 应为写表");
        assertTrue(result.getReadTables().isEmpty(), "简单 INSERT 不应有读表");
        assertTrue(result.getJoinTables().isEmpty(), "简单 INSERT 不应有 JOIN 表");
    }

    @Test
    void testUpdateExtractsWriteTables() {
        // UPDATE t_product — t_product 为写表
        SqlTableExtractor.SqlTableResult result = extractor.extractTables("UPDATE t_product SET price = 100 WHERE id = 1");

        assertNotNull(result);
        assertEquals("UPDATE", result.getSqlType());
        assertTrue(result.getWriteTables().contains("t_product"), "t_product 应为写表");
        assertTrue(result.getReadTables().isEmpty(), "当前逻辑 UPDATE 不提取读表");
        assertTrue(result.getJoinTables().isEmpty(), "简单 UPDATE 不应有 JOIN 表");
    }

    @Test
    void testDeleteExtractsWriteTables() {
        // DELETE t_log — t_log 为写表
        SqlTableExtractor.SqlTableResult result = extractor.extractTables("DELETE FROM t_log WHERE id = 42");

        assertNotNull(result);
        assertEquals("DELETE", result.getSqlType());
        assertTrue(result.getWriteTables().contains("t_log"), "t_log 应为写表");
        assertTrue(result.getReadTables().isEmpty(), "当前逻辑 DELETE 不提取读表");
        assertTrue(result.getJoinTables().isEmpty(), "简单 DELETE 不应有 JOIN 表");
    }

    @Test
    void testSelectWithJoinExtractsReadAndJoinTables() {
        // SELECT + JOIN — 应提取所有 FROM/JOIN 中的表为读表，JOIN 表也进入 joinTables
        SqlTableExtractor.SqlTableResult result = extractor.extractTables(
                "SELECT u.name, o.total FROM t_user u JOIN t_order o ON u.id = o.user_id WHERE o.status = 'PAID'"
        );

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());

        // 主表 t_user 和 JOIN 表 t_order 都应作为读表
        assertTrue(result.getReadTables().contains("t_user"), "FROM 表 t_user 应为读表");
        assertTrue(result.getReadTables().contains("t_order"), "JOIN 表 t_order 应为读表");
        // t_order 还应出现在 joinTables 中
        assertTrue(result.getJoinTables().contains("t_order"), "t_order 应为 JOIN 表");
        assertTrue(result.getWriteTables().isEmpty(), "SELECT+JOIN 不应有写表");
    }

    @Test
    void testIllegalSqlDoesNotThrowAndReturnsEmptyResult() {
        // 非法 SQL — 不抛异常，返回空结果
        SqlTableExtractor.SqlTableResult result = extractor.extractTables("THIS IS NOT VALID SQL @@@");

        // 不抛异常就成功
        assertNotNull(result);
        // 可能 sqlType 为 null（抛异常分支没有 set），或者为默认空值
        // 读/写/JOIN 表应该都是空
        assertTrue(result.getReadTables().isEmpty(), "非法 SQL 应无读表");
        assertTrue(result.getWriteTables().isEmpty(), "非法 SQL 应无写表");
        assertTrue(result.getJoinTables().isEmpty(), "非法 SQL 应无 JOIN 表");
    }

    @Test
    void testClassifyRelationType() {
        assertEquals("READS", SqlTableExtractor.classifyRelationType("SELECT"));
        assertEquals("WRITES", SqlTableExtractor.classifyRelationType("INSERT"));
        assertEquals("WRITES", SqlTableExtractor.classifyRelationType("UPDATE"));
        assertEquals("WRITES", SqlTableExtractor.classifyRelationType("DELETE"));
        assertEquals("READS", SqlTableExtractor.classifyRelationType("UNKNOWN"));
    }
}
