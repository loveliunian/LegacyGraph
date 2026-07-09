package io.github.legacygraph.verification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部工具验证结果，包含四类验证产物。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationResult {

    /** 外部工具确认存在的边（验证本地结果正确） */
    @Builder.Default
    private List<VerifiedEdge> confirmedEdges = new ArrayList<>();

    /** 外部工具发现但本地缺失的边（补漏） */
    @Builder.Default
    private List<VerifiedEdge> missingEdges = new ArrayList<>();

    /** 本地存在但外部工具未发现的边（可能误报） */
    @Builder.Default
    private List<VerifiedEdge> suspiciousEdges = new ArrayList<>();

    /** 外部工具独有的节点属性（如复杂度） */
    @Builder.Default
    private List<VerifiedNodeProperty> nodeProperties = new ArrayList<>();

    /** 来源适配器名称 */
    private String adapterName;

    /** 总检查数 */
    private int totalChecked;

    /** 确认数 */
    private int totalConfirmed;

    /**
     * 创建空结果（适配器不可用时使用）
     */
    public static VerificationResult empty(String adapterName) {
        return VerificationResult.builder()
                .adapterName(adapterName)
                .totalChecked(0)
                .totalConfirmed(0)
                .build();
    }
}
