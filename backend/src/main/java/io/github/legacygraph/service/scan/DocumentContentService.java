package io.github.legacygraph.service.scan;

import io.github.legacygraph.extractors.DocumentExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;

@Slf4j
@Service
public class DocumentContentService {

    public String readText(String filePath) throws Exception {
        // P0-2：.doc（老式 OLE2 二进制格式）检测——当前 DocumentExtractor.extractWord 用
        // XWPFDocument 只能解析 OOXML (.docx)，遇到 .doc 会抛 OfficeXmlFileException 等晦涩错误。
        // 这里前置检测并给出明确错误信息，避免静默丢失内容，便于用户感知需转换为 .docx。
        if (isLegacyDocFormat(filePath)) {
            log.warn("readText: 不支持 .doc 格式（XWPFDocument 仅支持 .docx）: {} —— 请将文档另存为 .docx 后重试", filePath);
            throw new IllegalArgumentException(
                    "不支持 .doc 格式文档（当前仅支持 .docx）：'" + filePath
                            + "'。请将文档用 Word 另存为 .docx 格式后重新扫描。");
        }
        DocumentExtractor extractor = new DocumentExtractor();
        return extractor.extractText(new File(filePath));
    }

    /** 检测是否为老式 .doc（非 .docx）格式，需排除 .docx 后缀避免误判。 */
    private static boolean isLegacyDocFormat(String filePath) {
        if (filePath == null) {
            return false;
        }
        String lower = filePath.toLowerCase();
        return lower.endsWith(".doc") && !lower.endsWith(".docx");
    }
}
