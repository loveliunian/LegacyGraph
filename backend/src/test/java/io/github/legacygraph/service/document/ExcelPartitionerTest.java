package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ExcelPartitioner} 单元测试 — 动态生成临时 .xlsx 验证解析。
 */
class ExcelPartitionerTest {

    private final ExcelPartitioner partitioner = new ExcelPartitioner();

    @Test
    void sheetToTable(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("data.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("结算表");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("字段");
            header.createCell(1).setCellValue("类型");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("id");
            r1.createCell(1).setCellValue("int");
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                wb.write(out);
            }
        }

        List<DocumentElement> elements = partitioner.partition("d1", "data.xlsx", file.toString());

        assertEquals(1, elements.size());
        DocumentElement table = elements.get(0);
        assertEquals(DocumentElement.Type.TABLE, table.getType());
        assertTrue(table.getText().contains("字段\t类型"), "text 应包含表头行");
        assertTrue(table.getText().contains("id\tint"), "text 应包含数据行");
        // firstRowNum=0,lastRowNum=1 → 1-based 1-2
        assertEquals("data.xlsx#结算表:1-2", table.getSourceLocation());
    }

    @Test
    void multipleSheets(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("multi.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet s1 = wb.createSheet("Sheet1");
            s1.createRow(0).createCell(0).setCellValue("a1");
            Sheet s2 = wb.createSheet("Sheet2");
            s2.createRow(0).createCell(0).setCellValue("b1");
            s2.createRow(1).createCell(0).setCellValue("b2");
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                wb.write(out);
            }
        }

        List<DocumentElement> elements = partitioner.partition("d1", "multi.xlsx", file.toString());

        assertEquals(2, elements.size());
        assertEquals("multi.xlsx#Sheet1:1-1", elements.get(0).getSourceLocation());
        assertEquals("multi.xlsx#Sheet2:1-2", elements.get(1).getSourceLocation());
        assertTrue(elements.get(0).getText().contains("a1"));
        assertTrue(elements.get(1).getText().contains("b1"));
        assertTrue(elements.get(1).getText().contains("b2"));
    }

    @Test
    void supports_xlsxXls() {
        assertTrue(partitioner.supports("a.xlsx"));
        assertTrue(partitioner.supports("a.xls"));
        assertFalse(partitioner.supports("a.csv"));
        assertFalse(partitioner.supports(null));
    }

    @Test
    void emptyContent_returnsEmpty() {
        assertTrue(partitioner.partition("d1", "data.xlsx", "").isEmpty());
        assertTrue(partitioner.partition("d1", "data.xlsx", null).isEmpty());
    }
}
