package io.github.legacygraph.connector;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 需求同步结果（阶段四）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncResult {

    /** 是否成功 */
    private boolean success;

    /** 同步的需求数量 */
    private int syncedCount;

    /** 创建的 Requirement 数量 */
    private int createdCount;

    /** 更新的 Requirement 数量 */
    private int updatedCount;

    /** 失败原因 */
    private String failureReason;

    /** 内部需求 ID（同步回源系统时使用） */
    private String internalRequirementId;
}
