package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;

import java.util.List;

/**
 * 文档切块器接口 — 针对特定文件类型的解析策略。
 * <p>
 * 约定：对于文本型文件（md/txt），{@code content} 为文件文本内容；
 * 对于二进制型文件（docx/xlsx），{@code content} 为文件路径（因二进制内容无法以字符串承载）。
 */
public interface DocumentPartitioner {

    /**
     * 将文档内容切分为结构化元素列表。
     *
     * @param docId    文档 ID
     * @param fileName 文件名（用于生成 sourceLocation 与类型判定）
     * @param content  文本内容（md/txt）或文件路径（docx/xlsx）
     * @return 结构化元素列表
     */
    List<DocumentElement> partition(String docId, String fileName, String content);

    /**
     * 是否支持该文件（按文件名/扩展名判定）。
     *
     * @param fileName 文件名
     * @return 支持返回 true
     */
    boolean supports(String fileName);
}
