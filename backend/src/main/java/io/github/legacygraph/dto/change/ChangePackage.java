package io.github.legacygraph.dto.change;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 物化的变更包（阶段二-2.2）。
 * <p>
 * PatchDraft 物化后的产物，包含完整的 unified diff 和可应用的变更集。
 * 可传递给沙箱执行验证或 PR 编排器。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangePackage {

    /** 关联的补丁草案 ID */
    private String patchDraftId;

    /** 关联的方案 ID */
    private String solutionId;

    private String projectId;

    private String versionId;

    /** 物化后的文件变更列表（含 unified diff） */
    private List<DraftFile> files;

    /** 完整的 unified diff 文本 */
    private String unifiedDiff;

    /** 物化时间 */
    private LocalDateTime materializedAt;
}
