package io.github.legacygraph.dto.scan;

import lombok.Builder;
import lombok.Data;

/**
 * 解析后的文档扫描范围。
 */
@Data
@Builder
public class ResolvedDocScope {

    private String docId;

    private String docName;

    private String filePath;
}
