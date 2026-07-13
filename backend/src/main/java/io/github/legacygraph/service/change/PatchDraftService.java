package io.github.legacygraph.service.change;

import io.github.legacygraph.dto.change.ChangePackage;
import io.github.legacygraph.dto.change.PatchValidationReport;
import io.github.legacygraph.entity.PatchDraft;

/**
 * 补丁草案服务 — 需求到编码的关键中间层（阶段二-2.2）。
 * <p>
 * 将已审批的 Solution 转换为可验证的 PatchDraft，
 * 每个 Draft 包含文件级 diff、证据引用、风险评估。
 * </p>
 * <p>核心流程：createDraft → validateDraft → materialize</p>
 */
public interface PatchDraftService {

    /**
     * 从 Solution 创建补丁草案。
     * <p>
     * 读取已审批方案的步骤（SolutionStep），转换为文件级 DraftFile，
     * 生成 unified diff，持久化为 PatchDraft（状态 DRAFT）。
     * </p>
     *
     * @param projectId  项目 ID
     * @param versionId  版本 ID
     * @param solutionId 方案 ID（须为 APPROVED 状态）
     * @return 落库的 PatchDraft（status=DRAFT）
     * @throws IllegalStateException 方案未审批或不存在
     */
    PatchDraft createDraft(String projectId, String versionId, String solutionId);

    /**
     * 校验草案：范围、格式、证据完整性。
     * <p>
     * 三类校验：
     * <ul>
     *   <li>范围校验：patch 文件属于影响子图</li>
     *   <li>格式校验：unified diff 格式正确</li>
     *   <li>证据校验：每个文件变更至少引用一个证据</li>
     * </ul>
     * </p>
     *
     * @param patchDraftId 补丁草案 ID
     * @return 校验报告
     */
    PatchValidationReport validateDraft(String patchDraftId);

    /**
     * 物化草案：生成完整 unified diff 并持久化。
     * <p>
     * 将所有 DraftFile 的 diff 合并为完整的 unified diff，
     * 产出 ChangePackage，更新草案状态为 MATERIALIZED。
     * </p>
     *
     * @param patchDraftId 补丁草案 ID（须已通过校验）
     * @return 物化的变更包
     * @throws IllegalStateException 草案未通过校验
     */
    ChangePackage materialize(String patchDraftId);
}
