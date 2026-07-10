package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;

import java.util.List;

/**
 * 文档结构化切块服务接口（spec 4.2）。
 * <p>
 * 根据文件名/类型将文档切分为 {@link DocumentElement} 列表，供后续向量化与检索使用。
 */
public interface DocumentPartitionService {

    /**
     * 将文档内容切分为结构化元素列表。
     *
     * @param docId    文档 ID
     * @param fileName 文件名（用于判定文件类型与生成 sourceLocation）
     * @param content  文本内容（md/txt）或文件路径（docx/xlsx）
     * @return 结构化元素列表
     */
    List<DocumentElement> partition(String docId, String fileName, String content);
}
