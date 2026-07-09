package io.github.legacygraph.verification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 外部工具验证的节点属性。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifiedNodeProperty {

    /** 节点 key */
    private String nodeKey;

    /** 属性名（如 "complexity"） */
    private String propertyName;

    /** 属性值 */
    private Object propertyValue;

    /** 来源工具名 */
    private String sourceTool;
}
