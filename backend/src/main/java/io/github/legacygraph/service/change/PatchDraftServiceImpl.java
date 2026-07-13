package io.github.legacygraph.service.change;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.change.ChangePackage;
import io.github.legacygraph.dto.change.DraftFile;
import io.github.legacygraph.dto.change.PatchValidationReport;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.PatchDraft;
import io.github.legacygraph.entity.Solution;
import io.github.legacygraph.entity.SolutionStep;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.PatchDraftRepository;
import io.github.legacygraph.repository.SolutionRepository;
import io.github.legacygraph.repository.SolutionStepRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 补丁草案服务实现（阶段二-2.2）。
 * <p>
 * 将已审批的 Solution 转换为可验证的 PatchDraft。核心流程：
 * <ol>
 *   <li>{@link #createDraft}：从 SolutionStep 构建 DraftFile，生成 unified diff</li>
 *   <li>{@link #validateDraft}：范围/格式/证据三类校验</li>
 *   <li>{@link #materialize}：合并 diff 产出 ChangePackage</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatchDraftServiceImpl implements PatchDraftService {

    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_VALIDATED = "VALIDATED";
    private static final String STATUS_MATERIALIZED = "MATERIALIZED";

    private final PatchDraftRepository patchDraftRepository;
    private final SolutionRepository solutionRepository;
    private final SolutionStepRepository stepRepository;
    private final CodeRepoRepository codeRepoRepository;
    private final PatchComposer patchComposer;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public PatchDraft createDraft(String projectId, String versionId, String solutionId) {
        // ① 校验方案存在且已审批
        Solution solution = solutionRepository.selectById(solutionId);
        if (solution == null) {
            throw new IllegalStateException("方案不存在: " + solutionId);
        }
        if (!STATUS_APPROVED.equals(solution.getStatus())) {
            throw new IllegalStateException(
                    "方案状态非 APPROVED，无法创建补丁草案: " + solution.getStatus());
        }

        // ② 查询方案步骤
        LambdaQueryWrapper<SolutionStep> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SolutionStep::getSolutionId, solutionId)
                .orderByAsc(SolutionStep::getStepIndex);
        List<SolutionStep> steps = stepRepository.selectList(wrapper);

        // ③ 解析项目本地仓库根路径（用于读取原文件生成 diff）
        String projectBasePath = resolveProjectBasePath(projectId);

        // ④ 将 SolutionStep 转换为 DraftFile
        List<DraftFile> draftFiles = new ArrayList<>();
        for (SolutionStep step : steps) {
            DraftFile draft = convertStepToDraftFile(step, projectBasePath);
            if (draft != null) {
                draftFiles.add(draft);
            }
        }

        if (draftFiles.isEmpty()) {
            log.warn("方案 {} 无可用的文件级步骤，创建空补丁草案", solutionId);
        }

        // ⑤ 风险评估
        String riskLevel = assessRisk(draftFiles);

        // ⑥ 持久化 PatchDraft
        PatchDraft draft = new PatchDraft();
        draft.setSolutionId(solutionId);
        draft.setProjectId(projectId);
        draft.setVersionId(versionId);
        draft.setStatus(STATUS_DRAFT);
        draft.setFilesJson(toJson(draftFiles));
        draft.setRiskLevel(riskLevel);
        draft.setGeneratedBy("llm");
        draft.setCreatedAt(LocalDateTime.now());
        patchDraftRepository.insert(draft);

        log.info("PatchDraft created: id={}, solutionId={}, files={}",
                draft.getId(), solutionId, draftFiles.size());
        return draft;
    }

    @Override
    public PatchValidationReport validateDraft(String patchDraftId) {
        PatchDraft draft = patchDraftRepository.selectById(patchDraftId);
        if (draft == null) {
            throw new IllegalStateException("补丁草案不存在: " + patchDraftId);
        }

        List<DraftFile> files = parseFilesJson(draft.getFilesJson());
        List<String> scopeErrors = new ArrayList<>();
        List<String> formatErrors = new ArrayList<>();
        List<String> evidenceErrors = new ArrayList<>();
        int passedFiles = 0;

        for (DraftFile file : files) {
            boolean filePassed = true;

            // 范围校验：文件路径非空
            if (file.getFilePath() == null || file.getFilePath().isBlank()) {
                scopeErrors.add("文件路径为空");
                filePassed = false;
            }

            // 格式校验：变更类型合法
            String changeType = file.getChangeType();
            if (changeType == null || !isValidChangeType(changeType)) {
                formatErrors.add("非法变更类型: " + changeType + " (文件: " + file.getFilePath() + ")");
                filePassed = false;
            }

            // 格式校验：diff 非空（DELETE 除外）
            if (!"DELETE".equals(changeType) && (file.getDiff() == null || file.getDiff().isBlank())) {
                formatErrors.add("diff 为空 (文件: " + file.getFilePath() + ")");
                filePassed = false;
            }

            // 格式校验：diff 包含 unified diff 头部
            if (file.getDiff() != null && !file.getDiff().isBlank()) {
                if (!file.getDiff().contains("---") || !file.getDiff().contains("+++")) {
                    formatErrors.add("diff 缺少 unified diff 头部 (文件: " + file.getFilePath() + ")");
                    filePassed = false;
                }
            }

            // 证据校验：每个文件变更至少引用一个证据
            if (file.getEvidenceIds() == null || file.getEvidenceIds().isEmpty()) {
                evidenceErrors.add("缺少证据引用 (文件: " + file.getFilePath() + ")");
                filePassed = false;
            }

            if (filePassed) {
                file.setValidationStatus("PASS");
                passedFiles++;
            } else {
                file.setValidationStatus("FAIL");
            }
        }

        boolean allPassed = scopeErrors.isEmpty() && formatErrors.isEmpty() && evidenceErrors.isEmpty();

        PatchValidationReport report = PatchValidationReport.builder()
                .passed(allPassed)
                .scopeErrors(scopeErrors)
                .formatErrors(formatErrors)
                .evidenceErrors(evidenceErrors)
                .totalFiles(files.size())
                .passedFiles(passedFiles)
                .riskLevel(draft.getRiskLevel())
                .build();

        // 更新草案状态和校验报告
        draft.setValidationReportJson(toJson(report));
        if (allPassed) {
            draft.setStatus(STATUS_VALIDATED);
            draft.setValidatedAt(LocalDateTime.now());
        }
        // 更新文件校验状态
        draft.setFilesJson(toJson(files));
        patchDraftRepository.updateById(draft);

        log.info("PatchDraft validated: id={}, passed={}, totalFiles={}, allPassed={}",
                patchDraftId, passedFiles, files.size(), allPassed);
        return report;
    }

    @Override
    @Transactional
    public ChangePackage materialize(String patchDraftId) {
        PatchDraft draft = patchDraftRepository.selectById(patchDraftId);
        if (draft == null) {
            throw new IllegalStateException("补丁草案不存在: " + patchDraftId);
        }

        // 校验状态：须已通过校验
        if (!STATUS_VALIDATED.equals(draft.getStatus()) && !STATUS_MATERIALIZED.equals(draft.getStatus())) {
            throw new IllegalStateException(
                    "补丁草案未通过校验，无法物化: status=" + draft.getStatus());
        }

        List<DraftFile> files = parseFilesJson(draft.getFilesJson());

        // 合并所有 diff 为完整 unified diff
        String unifiedDiff = files.stream()
                .map(DraftFile::getDiff)
                .filter(d -> d != null && !d.isBlank())
                .collect(Collectors.joining("\n"));

        ChangePackage changePackage = ChangePackage.builder()
                .patchDraftId(patchDraftId)
                .solutionId(draft.getSolutionId())
                .projectId(draft.getProjectId())
                .versionId(draft.getVersionId())
                .files(files)
                .unifiedDiff(unifiedDiff)
                .materializedAt(LocalDateTime.now())
                .build();

        // 更新草案状态为 MATERIALIZED
        draft.setStatus(STATUS_MATERIALIZED);
        draft.setMaterializedAt(LocalDateTime.now());
        patchDraftRepository.updateById(draft);

        log.info("PatchDraft materialized: id={}, unifiedDiffLength={}",
                patchDraftId, unifiedDiff.length());
        return changePackage;
    }

    // ==================== 内部方法 ====================

    /**
     * 将 SolutionStep 转换为 DraftFile。
     * <p>复用 {@link PatchComposer} 生成 unified diff，
     * 保留证据 ID、符号名等元数据。</p>
     */
    private DraftFile convertStepToDraftFile(SolutionStep step, String projectBasePath) {
        if (step == null) {
            return null;
        }
        String filePath = step.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            return null;
        }

        String actionType = step.getActionType() != null
                ? step.getActionType().toUpperCase() : "MODIFY";
        String codeSnippet = step.getCodeSnippet();

        // 读取原文件内容（MODIFY/DELETE 场景）
        String originalContent = null;
        if ("MODIFY".equals(actionType) || "DELETE".equals(actionType)) {
            originalContent = readFile(projectBasePath, filePath);
        }

        // MODIFY 场景原文件不存在则回退为 CREATE
        if ("MODIFY".equals(actionType)
                && (originalContent == null || originalContent.isEmpty())) {
            actionType = "CREATE";
        }

        String newContent = "DELETE".equals(actionType) ? null : codeSnippet;
        String diff = patchComposer.composeDiff(filePath, originalContent, newContent, actionType);

        List<String> evidenceIds = parseEvidenceIds(step.getEvidenceIds());
        List<String> symbolNames = step.getSymbolName() != null
                ? List.of(step.getSymbolName()) : Collections.emptyList();

        return DraftFile.builder()
                .filePath(filePath)
                .changeType(actionType)
                .originalContent(originalContent)
                .newContent(newContent)
                .diff(diff)
                .evidenceIds(evidenceIds)
                .symbolNames(symbolNames)
                .validationStatus("PENDING")
                .build();
    }

    /** 简单风险评估：DELETE 高风险，CREATE 中风险，MODIFY 低风险 */
    private String assessRisk(List<DraftFile> files) {
        if (files.stream().anyMatch(f -> "DELETE".equals(f.getChangeType()))) {
            return "HIGH";
        }
        if (files.stream().anyMatch(f -> "CREATE".equals(f.getChangeType()))) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private boolean isValidChangeType(String type) {
        return "CREATE".equals(type) || "MODIFY".equals(type) || "DELETE".equals(type);
    }

    private String resolveProjectBasePath(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        try {
            List<CodeRepo> repos = codeRepoRepository.selectList(
                    new LambdaQueryWrapper<CodeRepo>()
                            .eq(CodeRepo::getProjectId, projectId)
                            .isNotNull(CodeRepo::getLocalPath));
            for (CodeRepo repo : repos) {
                if (repo.getLocalPath() != null && !repo.getLocalPath().isBlank()) {
                    return repo.getLocalPath();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve projectBasePath for project {}: {}", projectId, e.getMessage());
        }
        return null;
    }

    private String readFile(String projectBasePath, String filePath) {
        if (projectBasePath == null || projectBasePath.isBlank()) {
            return null;
        }
        try {
            java.nio.file.Path fullPath = java.nio.file.Paths.get(projectBasePath, filePath);
            if (!java.nio.file.Files.exists(fullPath)) {
                return null;
            }
            return java.nio.file.Files.readString(fullPath, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取原文件失败 {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<DraftFile> parseFilesJson(String filesJson) {
        if (filesJson == null || filesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(filesJson, new TypeReference<List<DraftFile>>() {});
        } catch (Exception e) {
            log.warn("解析 filesJson 失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> parseEvidenceIds(String evidenceIdsJson) {
        if (evidenceIdsJson == null || evidenceIdsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(evidenceIdsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // 降级：简单解析
            String trimmed = evidenceIdsJson.trim();
            if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
            if (trimmed.endsWith("]")) trimmed = trimmed.substring(0, trimmed.length() - 1);
            if (trimmed.isBlank()) return Collections.emptyList();
            List<String> result = new ArrayList<>();
            for (String part : trimmed.split(",")) {
                String id = part.trim().replaceAll("^\"|\"$", "");
                if (!id.isEmpty()) result.add(id);
            }
            return result;
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
