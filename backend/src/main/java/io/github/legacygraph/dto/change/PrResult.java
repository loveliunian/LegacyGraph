package io.github.legacygraph.dto.change;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PR 创建结果（阶段三-3.2）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrResult {

    /** 是否成功 */
    private boolean success;

    /** PR URL */
    private String prUrl;

    /** PR 编号 */
    private String prNumber;

    /** 分支名 */
    private String branchName;

    /** 失败原因 */
    private String failureReason;

    /** 提交 SHA */
    private String commitSha;
}
