package io.github.legacygraph.agent.adapter;

import io.github.legacygraph.agent.MigrationAgent;
import io.github.legacygraph.dto.MigrationConversion;
import io.github.legacygraph.dto.graph.PatchPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MigrationAgent → PatchPlan 适配器（增强版2）。
 * <p>
 * 复用现有 {@link MigrationAgent}（返回 {@link MigrationConversion}），把迁移转换结果
 * 包装为 PatchPlan。区分确定性改写（有 migratedCode）与需人工复核项（manualReviewNeeded）。
 * 对确定性升级/迁移，后续可进一步映射到 OpenRewrite recipe（见 doc §工具选型表）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationAgentAdapter {

    private final MigrationAgent migrationAgent;

    /**
     * 生成迁移 PatchPlan 草案。
     *
     * @param taskId             变更任务ID
     * @param projectId          项目ID
     * @param migrationDirection 迁移方向（如 SpringBoot2_to_3）
     * @param sourcePath         源文件路径
     * @param code               源代码
     * @param customRules        自定义规则（可空）
     * @param evidenceIds        证据ID
     */
    public PatchPlan toPatchPlan(String taskId, String projectId, String migrationDirection,
                                 String sourcePath, String code, String customRules,
                                 List<String> evidenceIds) {
        MigrationConversion conversion = migrationAgent.convert(
                projectId, migrationDirection, sourcePath, code, customRules);

        boolean needsReview = conversion == null
                || (conversion.getManualReviewNeeded() != null && !conversion.getManualReviewNeeded().isEmpty());

        PatchPlan.Patch patch = PatchPlan.Patch.builder()
                .filePath(sourcePath)
                .changeType("MODIFY")
                .patchText(conversion != null ? conversion.getMigratedCode() : null)
                .evidenceIds(evidenceIds)
                .build();

        PatchPlan.ImpactedFile impacted = PatchPlan.ImpactedFile.builder()
                .path(sourcePath)
                .reason(conversion != null ? conversion.getSummary() : "迁移转换")
                .build();

        log.info("MigrationAgentAdapter produced PatchPlan draft for task={}, direction={}, needsReview={}",
                taskId, migrationDirection, needsReview);

        return PatchPlan.builder()
                .taskId(taskId)
                .taskType("UPGRADE")
                // 升级/迁移风险默认偏高，涉及编译与回滚
                .riskLevel("HIGH")
                .impactedFiles(List.of(impacted))
                .patches(List.of(patch))
                .validationGates(List.of("STATIC", "UNIT", "MIGRATION"))
                .manualReviewNeeded(needsReview)
                .generatedBy("MigrationAgent")
                .build();
    }
}
