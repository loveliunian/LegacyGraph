package io.github.legacygraph.dto.change;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PR 状态信息（阶段三-3.2）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrStatusInfo {

    /** PR URL */
    private String prUrl;

    /** PR 状态：OPEN / MERGED / CLOSED / CONFLICT / PENDING */
    private String status;

    /** CI 状态：PENDING / SUCCESS / FAILURE / RUNNING */
    private String ciStatus;

    /** 审核人数 */
    private int approvedReviewers;

    /** 所需审核人数 */
    private int requiredReviewers;

    /** 是否可合并 */
    private boolean mergeable;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;
}
