package io.github.legacygraph.service;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.extractors.DatabaseConstraintExtractor;
import io.github.legacygraph.extractors.DatabaseMetadataExtractor.TableMetadata;
import io.github.legacygraph.repository.DbConnectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.scan.DatabaseMetadataScanService;

/**
 * 数据库元数据扫描服务单元测试。
 * <p>
 * 使用 Mockito 模拟 DataSource / Connection / DatabaseMetaData / ResultSet，
 * 不依赖真实数据库连接。验证扫描流程、Schema 默认值、数据库类型差异和异常处理。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class DatabaseMetadataScanServiceTest {

    @Mock
    private GraphBuilder graphBuilder;

    @Mock
    private DatabaseConstraintExtractor databaseConstraintExtractor;

    @Mock
    private DbConnectionRepository dbConnectionRepository;

    @Mock
    private DataSource mockDataSource;

    @Mock
    private Connection mockConnection;

    @Mock
    private DatabaseMetaData mockMetaData;

    @Mock
    private ResultSet mockTablesResultSet;

    @InjectMocks
    private DatabaseMetadataScanService databaseMetadataScanService;

    /**
     * 测试：扫描空 Schema（无表），应返回 0 并正常调用 buildDatabaseGraph。
     */
    @Test
    void testScan_EmptySchema() throws Exception {
        // 准备 Mock 链：DataSource → Connection → DatabaseMetaData → ResultSet
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        // 使用 any() 匹配 getTables 参数（避免参数精确匹配问题）
        when(mockMetaData.getTables(any(), any(), any(), any())).thenReturn(mockTablesResultSet);
        when(mockTablesResultSet.next()).thenReturn(false); // 模拟无表

        int count = databaseMetadataScanService.scan("proj-1", "v1", mockDataSource, "public", "postgresql");

        // 无表时应返回 0
        assertEquals(0, count);
        // 验证 buildDatabaseGraph 被调用（传入空列表）
        verify(graphBuilder).buildDatabaseGraph(eq("proj-1"), eq("v1"), argThat(list ->
                list != null && list.isEmpty()));
    }

    /**
     * 测试：传入 null schema 时，默认使用 "public"。
     */
    @Test
    void testScan_NullSchemaDefaultsToPublic() throws Exception {
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getTables(any(), any(), any(), any())).thenReturn(mockTablesResultSet);
        when(mockTablesResultSet.next()).thenReturn(false);

        int count = databaseMetadataScanService.scan("proj-1", "v1", mockDataSource, null, "postgresql");

        assertTrue(count >= 0);
        // 验证传入 extractFromSchema 的 schema 是 "public"
        verify(mockMetaData).getTables(isNull(), eq("public"), eq("%"), any());
    }

    /**
     * 测试：MySQL 场景（isMySql=true），schema 传 null 给 extractor。
     */
    @Test
    void testScan_WithMySql() throws Exception {
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getTables(any(), any(), any(), any())).thenReturn(mockTablesResultSet);
        when(mockTablesResultSet.next()).thenReturn(false);

        int count = databaseMetadataScanService.scan("proj-1", "v1", mockDataSource, "mydb", "mysql");

        assertTrue(count >= 0);
        // MySQL 场景：catalog = conn.getCatalog()，schemaPattern = null
        verify(mockConnection, atLeastOnce()).getCatalog();
        verify(graphBuilder).buildDatabaseGraph(eq("proj-1"), eq("v1"), anyList());
    }

    /**
     * 测试：连接失败时 catch 异常并返回 0（不中断业务流程）。
     */
    @Test
    void testScan_OnException_ReturnsZero() throws Exception {
        when(mockDataSource.getConnection()).thenThrow(new RuntimeException("连接失败"));

        int count = databaseMetadataScanService.scan("proj-1", "v1", mockDataSource, "public", "postgresql");

        assertEquals(0, count);
        // 异常时不应调用 buildDatabaseGraph
        verify(graphBuilder, never()).buildDatabaseGraph(any(), any(), anyList());
    }

    /**
     * 测试：MariaDB 也走 MySQL 路径（isMySql=true）。
     */
    @Test
    void testScan_MariaDb_TreatedAsMySql() throws Exception {
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getTables(any(), any(), any(), any())).thenReturn(mockTablesResultSet);
        when(mockTablesResultSet.next()).thenReturn(false);

        int count = databaseMetadataScanService.scan("proj-1", "v1", mockDataSource, null, "mariadb");

        assertTrue(count >= 0);
        // MariaDB 应触发 conn.getCatalog()
        verify(mockConnection, atLeastOnce()).getCatalog();
    }

    /**
     * 测试：空白字符串 schema 等同于 null，使用 "public" 默认值。
     */
    @Test
    void testScan_BlankSchemaDefaultsToPublic() throws Exception {
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getTables(any(), any(), any(), any())).thenReturn(mockTablesResultSet);
        when(mockTablesResultSet.next()).thenReturn(false);

        int count = databaseMetadataScanService.scan("proj-1", "v1", mockDataSource, "   ", "postgresql");

        assertTrue(count >= 0);
        // 空白 schema 应被替换为 "public"
        verify(mockMetaData).getTables(isNull(), eq("public"), eq("%"), any());
    }

    @Test
    void testScan_ExtractsConstraintsAndBuildsConstraintGraph() throws Exception {
        DatabaseConstraintExtractor.ForeignKeyInfo fk = new DatabaseConstraintExtractor.ForeignKeyInfo();
        fk.setFkName("fk_order_customer");
        fk.setFkTableName("orders");
        fk.setFkColumnName("customer_id");
        fk.setPkTableName("customers");
        fk.setPkColumnName("id");

        DatabaseConstraintExtractor.IndexInfo idx = new DatabaseConstraintExtractor.IndexInfo();
        idx.setIndexName("uk_orders_no");
        idx.setTableName("orders");
        idx.setColumnNames(java.util.List.of("order_no"));
        idx.setNonUnique(false);
        idx.setUnique(true);

        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getTables(any(), any(), any(), any())).thenReturn(mockTablesResultSet);
        when(mockTablesResultSet.next()).thenReturn(false);
        when(databaseConstraintExtractor.extractForeignKeys(mockDataSource, null, "public"))
                .thenReturn(List.of(fk));
        when(databaseConstraintExtractor.extractIndexes(mockDataSource, null, "public"))
                .thenReturn(List.of(idx));

        int count = databaseMetadataScanService.scan("proj-1", "v1", mockDataSource, "public", "postgresql");

        assertEquals(0, count);
        verify(databaseConstraintExtractor).extractForeignKeys(mockDataSource, null, "public");
        verify(databaseConstraintExtractor).extractIndexes(mockDataSource, null, "public");
        verify(graphBuilder).buildDatabaseConstraintGraph("proj-1", "v1", "public", List.of(fk), List.of(idx));
    }

    @Test
    void testScan_WhenSchemaFingerprintUnchanged_StillRebuildsGraph() throws Exception {
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getTables(any(), any(), any(), any())).thenReturn(mockTablesResultSet);
        when(mockTablesResultSet.next()).thenReturn(false);

        DbConnection connection = new DbConnection();
        connection.setId("db-1");
        connection.setProjectId("proj-1");
        connection.setSchemaName("public");
        connection.setDbType("postgresql");
        connection.setSchemaFingerprint("");

        int count = databaseMetadataScanService.scan("proj-1", "v1", mockDataSource, connection);

        assertEquals(0, count);
        // 即使 fingerprint 相同也重建图谱（保证数据一致性）
        verify(graphBuilder, times(1)).buildDatabaseGraph(any(), any(), anyList());
    }

    @Test
    void testScan_WithMaxTables_LimitsTablesBeforeGraphBuild() throws Exception {
        ResultSet emptyColumns = mock(ResultSet.class);
        ResultSet emptyPrimaryKeys = mock(ResultSet.class);

        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockMetaData.getTables(any(), any(), any(), any())).thenReturn(mockTablesResultSet);
        when(mockTablesResultSet.next()).thenReturn(true, true, true, false);
        when(mockTablesResultSet.getString("TABLE_CAT")).thenReturn(null);
        when(mockTablesResultSet.getString("TABLE_SCHEM")).thenReturn("public");
        when(mockTablesResultSet.getString("TABLE_NAME")).thenReturn("t1", "t2", "t3");
        when(mockMetaData.getColumns(any(), any(), anyString(), anyString())).thenReturn(emptyColumns);
        when(emptyColumns.next()).thenReturn(false);
        when(mockMetaData.getPrimaryKeys(any(), any(), anyString())).thenReturn(emptyPrimaryKeys);
        when(emptyPrimaryKeys.next()).thenReturn(false);
        when(databaseConstraintExtractor.extractForeignKeys(mockDataSource, null, "public")).thenReturn(List.of());
        when(databaseConstraintExtractor.extractIndexes(mockDataSource, null, "public")).thenReturn(List.of());

        DbConnection connection = new DbConnection();
        connection.setId("db-1");
        connection.setProjectId("proj-1");
        connection.setSchemaName("public");
        connection.setDbType("postgresql");

        int count = databaseMetadataScanService.scan("proj-1", "v1", mockDataSource, connection, 2);

        assertEquals(2, count);
        verify(graphBuilder).buildDatabaseGraph(eq("proj-1"), eq("v1"), argThat((List<TableMetadata> tables) ->
                tables.size() == 2
                        && tables.stream().map(TableMetadata::getTableName).toList().equals(List.of("t1", "t2"))));
    }
}
