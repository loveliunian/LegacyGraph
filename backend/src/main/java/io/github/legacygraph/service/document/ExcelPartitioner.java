package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Excel 文档切块器（spec 4.5）。
 * <p>
 * 使用 Apache POI 按 Sheet 切分：每个 Sheet 的数据区域生成一个 TABLE 元素。
 * sourceLocation 格式：file.xlsx#sheet:rowStart-rowEnd（行号 1-based）。
 * 因二进制内容无法以字符串承载，{@code content} 参数约定为 .xlsx 文件路径。
 */
public class ExcelPartitioner implements DocumentPartitioner {

    private static final DataFormatter FORMATTER = new DataFormatter();

    @Override
    public List<DocumentElement> partition(String docId, String fileName, String content) {
        List<DocumentElement> elements = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return elements;
        }
        try (InputStream in = new FileInputStream(content);
             Workbook workbook = WorkbookFactory.create(in)) {
            int index = 0;
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                String sheetName = sheet.getSheetName();
                int first = sheet.getFirstRowNum();
                int last = sheet.getLastRowNum();
                if (first < 0 || last < 0 || last < first) {
                    // 空 Sheet
                    continue;
                }
                StringBuilder text = new StringBuilder();
                for (int r = first; r <= last; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        text.append('\n');
                        continue;
                    }
                    short firstCol = row.getFirstCellNum();
                    short lastCol = row.getLastCellNum();
                    if (lastCol < 0) {
                        text.append('\n');
                        continue;
                    }
                    StringBuilder rowText = new StringBuilder();
                    for (int c = firstCol; c < lastCol; c++) {
                        Cell cell = row.getCell(c);
                        if (cell != null && cell.getCellType() != CellType.BLANK) {
                            rowText.append(FORMATTER.formatCellValue(cell));
                        }
                        if (c < lastCol - 1) {
                            rowText.append('\t');
                        }
                    }
                    text.append(rowText).append('\n');
                }
                String tableText = text.toString();
                if (tableText.endsWith("\n")) {
                    tableText = tableText.substring(0, tableText.length() - 1);
                }
                // 行号转 1-based
                String sourceLocation = fileName + "#" + sheetName + ":" + (first + 1) + "-" + (last + 1);
                elements.add(DocumentElement.builder()
                        .id(UUID.randomUUID().toString())
                        .docId(docId)
                        .type(DocumentElement.Type.TABLE)
                        .text(tableText)
                        .headingPath(Collections.emptyList())
                        .sourceLocation(sourceLocation)
                        .build());
                index++;
            }
            return elements;
        } catch (IOException e) {
            throw new IllegalStateException("解析 Excel 文档失败: " + content, e);
        }
    }

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }
}
