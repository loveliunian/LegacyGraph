package io.github.legacygraph.dto.scan;

import java.util.List;

/**
 * 质量门禁决策结果。
 * <p>
 * 由 {@link io.github.legacygraph.service.scan.GraphQualityGate#evaluate} 返回，
 * 表示某次图谱质量门禁评估是否通过及未通过的具体原因。
 * </p>
 *
 * @param passed  是否通过门禁
 * @param reasons 未通过的规则名称列表；通过时为空列表
 */
public record Decision(boolean passed, List<String> reasons) {
}
