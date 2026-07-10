package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 默认文档切块服务实现（spec 4.2）。
 * <p>
 * 根据文件扩展名分发到对应 {@link DocumentPartitioner}：
 * <ul>
 *   <li>.md → {@link MarkdownPartitioner}</li>
 *   <li>.docx → {@link WordPartitioner}</li>
 *   <li>.xlsx/.xls → {@link ExcelPartitioner}</li>
 *   <li>.txt/.text → {@link PlainTextPartitioner}</li>
 *   <li>.pdf → 暂不实现（后续按需补充）</li>
 * </ul>
 */
@Slf4j
@Service
public class DefaultDocumentPartitionService implements DocumentPartitionService {

    private final List<DocumentPartitioner> partitioners;

    public DefaultDocumentPartitionService() {
        this.partitioners = List.of(
                new MarkdownPartitioner(),
                new WordPartitioner(),
                new ExcelPartitioner(),
                new PlainTextPartitioner());
    }

    @Override
    public List<DocumentElement> partition(String docId, String fileName, String content) {
        if (fileName == null || fileName.isBlank()) {
            log.warn("partition: 文件名为空，按纯文本处理");
            return new PlainTextPartitioner().partition(docId, "unknown.txt", content == null ? "" : content);
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            log.warn("partition: PDF 解析暂未实现: {}", fileName);
            throw new UnsupportedOperationException("PDF 解析暂未实现: " + fileName);
        }
        for (DocumentPartitioner partitioner : partitioners) {
            if (partitioner.supports(fileName)) {
                return partitioner.partition(docId, fileName, content);
            }
        }
        log.warn("partition: 未识别的文件类型，按纯文本处理: {}", fileName);
        return new PlainTextPartitioner().partition(docId, fileName, content == null ? "" : content);
    }
}
