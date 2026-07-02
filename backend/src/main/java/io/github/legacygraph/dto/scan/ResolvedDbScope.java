package io.github.legacygraph.dto.scan;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 解析后的数据库扫描范围。
 */
@Data
@Builder
public class ResolvedDbScope {

    private String connectionId;

    private String dbType;

    private String schemaName;

    /** 包含的表模式 */
    private List<String> includeTables;

    /** 排除的表模式 */
    private List<String> excludeTables;
}
