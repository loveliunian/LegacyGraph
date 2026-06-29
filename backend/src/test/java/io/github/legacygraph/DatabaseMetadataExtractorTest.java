package io.github.legacygraph;

import io.github.legacygraph.extractors.DatabaseMetadataExtractor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMetadataExtractorTest {

    @Test
    void testTableMetadata_DataClass() {
        DatabaseMetadataExtractor.TableMetadata table = new DatabaseMetadataExtractor.TableMetadata();
        table.setTableCatalog("mycat");
        table.setTableSchema("public");
        table.setTableName("t_user");
        table.setTableComment("用户表");
        table.setColumns(new ArrayList<>());

        assertEquals("mycat", table.getTableCatalog());
        assertEquals("public", table.getTableSchema());
        assertEquals("t_user", table.getTableName());
        assertEquals("用户表", table.getTableComment());
        assertTrue(table.getColumns().isEmpty());
    }

    @Test
    void testColumnMetadata_DataClass() {
        DatabaseMetadataExtractor.ColumnMetadata col = new DatabaseMetadataExtractor.ColumnMetadata();
        col.setColumnName("id");
        col.setDataType("4");
        col.setTypeName("BIGINT");
        col.setColumnSize(19);
        col.setNullable(false);
        col.setColumnDefault("nextval('seq')");
        col.setColumnComment("主键ID");
        col.setPrimaryKey(true);
        col.setForeignKey(false);
        col.setReferencedTableName(null);
        col.setReferencedColumnName(null);
        col.setSemanticType("id");

        assertEquals("id", col.getColumnName());
        assertEquals("BIGINT", col.getTypeName());
        assertEquals(19, col.getColumnSize());
        assertFalse(col.getNullable());
        assertTrue(col.getPrimaryKey());
        assertEquals("id", col.getSemanticType());
    }

    @Test
    void testExtractFromSchema_ThrowsOnNullDataSource() {
        DatabaseMetadataExtractor extractor = new DatabaseMetadataExtractor();
        assertThrows(Exception.class, () ->
                extractor.extractFromSchema(null, "public"));
    }
}
