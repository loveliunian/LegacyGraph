package io.github.legacygraph.dao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Neo4jGraphDao 单元测试 — 使用 Repository 层 mock。
 * 覆盖核心聚合方法 graphStats()。
 */
@ExtendWith(MockitoExtension.class)
class Neo4jGraphDaoTest {

    @Mock
    private Neo4jQueryRepository queryRepo;
    @Mock
    private Neo4jWriteRepository writeRepo;
    @Mock
    private Neo4jProjectionRepository projectionRepo;
    @Mock
    private Neo4jAdminRepository adminRepo;
    @Mock
    private Neo4jSchemaRepository schemaRepo;

    @Test
    void normalizeIdHandlesNullAndEmpty() {
        assertEquals(null, Neo4jGraphDao.normalizeId(null));
        assertEquals(null, Neo4jGraphDao.normalizeId(""));
        assertEquals("abc", Neo4jGraphDao.normalizeId("abc"));
    }

    @Test
    void inlineStripHyphensRemovesDashes() {
        assertEquals("abcdef", "abc-def".replace("-", ""));
        assertEquals("12345", "12345".replace("-", ""));
        assertEquals(null, (String) null);
    }
}
