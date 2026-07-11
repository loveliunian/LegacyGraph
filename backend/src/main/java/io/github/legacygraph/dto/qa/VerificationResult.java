package io.github.legacygraph.dto.qa;

import io.github.legacygraph.dto.EvidenceItem;

import java.util.List;

/**
 * 证据验证结果 — 由 {@link io.github.legacygraph.service.qa.EvidenceVerifier} 产出。
 *
 * <p>包含两项核心指标：</p>
 * <ul>
 *   <li>{@link #evidenceCoverage()} — 答案声明中有证据支撑的比例（0.0~1.0）</li>
 *   <li>{@link #evidenceReliability()} — 证据可靠度（0.0~1.0），综合存在性/归属/ACL/sourceLocation 校验</li>
 * </ul>
 *
 * @param verified          是否通过验证（coverage >= 阈值且无致命错误）
 * @param evidenceCoverage  答案声明中有证据支撑的比例
 * @param evidenceReliability 证据可靠度
 * @param matchedClaims     答案中与证据匹配的声明列表
 * @param unmatchedClaims   答案中无证据支撑的声明列表
 * @param verifiedEvidences  通过校验的证据列表（已过滤掉失效证据）
 * @param violations        校验中发现的违规项（证据不存在/归属不符/ACL 不可访问/sourceLocation 为空）
 */
public record VerificationResult(
        boolean verified,
        double evidenceCoverage,
        double evidenceReliability,
        List<String> matchedClaims,
        List<String> unmatchedClaims,
        List<EvidenceItem> verifiedEvidences,
        List<String> violations
) {

    /** 低覆盖率阈值：低于此值标记 LOW_CONFIDENCE */
    public static final double LOW_COVERAGE_THRESHOLD = 0.6;

    /**
     * 构造方法 — 对 null 列表做防御性处理。
     */
    public VerificationResult {
        matchedClaims = matchedClaims == null ? List.of() : List.copyOf(matchedClaims);
        unmatchedClaims = unmatchedClaims == null ? List.of() : List.copyOf(unmatchedClaims);
        verifiedEvidences = verifiedEvidences == null ? List.of() : List.copyOf(verifiedEvidences);
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /**
     * 是否为低覆盖率（低于 {@link #LOW_COVERAGE_THRESHOLD}）。
     */
    public boolean isLowCoverage() {
        return evidenceCoverage < LOW_COVERAGE_THRESHOLD;
    }
}
