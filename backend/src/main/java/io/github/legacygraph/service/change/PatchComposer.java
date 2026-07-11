package io.github.legacygraph.service.change;

import io.github.legacygraph.dto.change.ChangeTaskProposal;
import io.github.legacygraph.entity.SolutionStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 补丁组合器（G-12）。
 * <p>
 * 将 {@link SolutionStep#getCodeSnippet()} 序列化为 unified diff 格式，
 * 产出 {@link ChangeTaskProposal.ProposalFile}，作为方案桥接到变更任务时的初始提案。
 * </p>
 * <p>变更类型处理：</p>
 * <ul>
 *   <li>MODIFY：读取项目原文件，用 codeSnippet 作为新版本生成 unified diff</li>
 *   <li>CREATE：生成新文件 diff（/dev/null → b/path）</li>
 *   <li>DELETE：生成文件删除 diff（a/path → /dev/null）</li>
 *   <li>MODIFY 但原文件不存在：回退为 CREATE</li>
 * </ul>
 * <p>项目无第三方 diff 库，采用手写 LCS（最长公共子序列）算法生成 unified diff。</p>
 */
@Slf4j
@Service
public class PatchComposer {

    /** unified diff 每个变更块前后的上下文行数 */
    private static final int CONTEXT_LINES = 3;

    /**
     * 生成 unified diff 文本。
     *
     * @param filePath        文件路径（用于 diff 头部 a/ b/ 标识）
     * @param originalContent 原始文件内容（MODIFY 场景；CREATE 时为 null）
     * @param newContent       新文件内容（CREATE/MODIFY 场景；DELETE 时为 null）
     * @param changeType      变更类型：CREATE / MODIFY / DELETE
     * @return unified diff 文本
     */
    public String composeDiff(String filePath, String originalContent,
                              String newContent, String changeType) {
        String type = changeType != null ? changeType.toUpperCase() : "MODIFY";

        switch (type) {
            case "DELETE":
                return composeDeleteDiff(filePath, originalContent);
            case "CREATE":
                return composeCreateDiff(filePath, newContent);
            case "MODIFY":
            default:
                // MODIFY 场景：原文件不存在则回退为 CREATE
                if (originalContent == null || originalContent.isEmpty()) {
                    return composeCreateDiff(filePath, newContent);
                }
                if (newContent == null || newContent.isEmpty()) {
                    // 新内容为空，视为删除文件
                    return composeDeleteDiff(filePath, originalContent);
                }
                return composeModifyDiff(filePath, originalContent, newContent);
        }
    }

    /**
     * 为单个方案步骤生成补丁提案文件。
     * <p>根据步骤的 actionType 读取原文件（MODIFY/DELETE）或直接使用 codeSnippet（CREATE），
     * 调用 {@link #composeDiff} 生成 unified diff，封装为 {@link ChangeTaskProposal.ProposalFile}。</p>
     *
     * @param step            方案步骤（需含 codeSnippet、filePath、actionType）
     * @param projectBasePath 项目本地仓库根路径（用于读取原文件）
     * @return 补丁提案文件；若步骤缺少 codeSnippet 或 filePath 则返回 null
     */
    public ChangeTaskProposal.ProposalFile composePatch(SolutionStep step, String projectBasePath) {
        if (step == null) {
            return null;
        }
        String filePath = step.getFilePath();
        String codeSnippet = step.getCodeSnippet();
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        if (codeSnippet == null || codeSnippet.isBlank()) {
            // DELETE 步骤可能无 codeSnippet，继续处理
        }

        String actionType = step.getActionType() != null
                ? step.getActionType().toUpperCase() : "MODIFY";

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
        String diff = composeDiff(filePath, originalContent, newContent, actionType);

        List<String> evidenceIds = parseEvidenceIds(step.getEvidenceIds());

        return ChangeTaskProposal.ProposalFile.builder()
                .filePath(filePath)
                .op(actionType)
                .symbolName(step.getSymbolName())
                .diff(diff)
                .evidenceIds(evidenceIds)
                .testDescription(step.getTestDescription())
                .rollbackDescription(step.getRollbackDescription())
                .build();
    }

    // ==================== unified diff 生成核心 ====================

    /**
     * 生成新文件 diff（/dev/null → b/path）。
     */
    private String composeCreateDiff(String filePath, String newContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- /dev/null\n");
        sb.append("+++ b/").append(filePath).append("\n");

        String[] newLines = splitLines(newContent);
        if (newLines.length == 0) {
            sb.append("@@ -0,0 +0,0 @@\n");
            return sb.toString();
        }
        sb.append(String.format("@@ -0,0 +1,%d @@\n", newLines.length));
        for (String line : newLines) {
            sb.append("+").append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * 生成文件删除 diff（a/path → /dev/null）。
     */
    private String composeDeleteDiff(String filePath, String originalContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(filePath).append("\n");
        sb.append("+++ /dev/null\n");

        String[] oldLines = splitLines(originalContent);
        if (oldLines.length == 0) {
            sb.append("@@ -0,0 +0,0 @@\n");
            return sb.toString();
        }
        sb.append(String.format("@@ -1,%d +0,0 @@\n", oldLines.length));
        for (String line : oldLines) {
            sb.append("-").append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * 生成修改 diff：基于 LCS 算法逐行比较，输出带上下文的 unified diff hunk。
     */
    private String composeModifyDiff(String filePath, String originalContent, String newContent) {
        String[] oldLines = splitLines(originalContent);
        String[] newLines = splitLines(newContent);

        // 计算 LCS 并生成编辑操作序列
        List<DiffOp> ops = computeDiffOps(oldLines, newLines);

        // 分组为 hunk 并输出
        List<Hunk> hunks = groupHunks(ops);

        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(filePath).append("\n");
        sb.append("+++ b/").append(filePath).append("\n");

        for (Hunk hunk : hunks) {
            sb.append(hunk.header).append("\n");
            for (DiffOp op : hunk.ops) {
                switch (op.type) {
                    case EQUAL -> sb.append(" ").append(oldLines[op.oldIndex]).append("\n");
                    case DELETE -> sb.append("-").append(oldLines[op.oldIndex]).append("\n");
                    case INSERT -> sb.append("+").append(newLines[op.newIndex]).append("\n");
                }
            }
        }
        return sb.toString();
    }

    // ==================== LCS diff 算法 ====================

    /** 编辑操作类型 */
    private enum OpType { EQUAL, DELETE, INSERT }

    /** 单个编辑操作 */
    private record DiffOp(OpType type, int oldIndex, int newIndex) {}

    /** 一个 hunk（变更块） */
    private record Hunk(String header, List<DiffOp> ops) {}

    /**
     * 基于 LCS 动态规划计算编辑操作序列。
     *
     * @param oldLines 旧文件行数组
     * @param newLines 新文件行数组
     * @return 编辑操作序列（EQUAL/DELETE/INSERT）
     */
    private List<DiffOp> computeDiffOps(String[] oldLines, String[] newLines) {
        int m = oldLines.length;
        int n = newLines.length;

        // LCS DP 表：dp[i][j] = oldLines[i:] 与 newLines[j:] 的 LCS 长度
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (oldLines[i].equals(newLines[j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        // 回溯生成操作序列
        List<DiffOp> ops = new ArrayList<>(m + n);
        int i = 0, j = 0;
        while (i < m && j < n) {
            if (oldLines[i].equals(newLines[j])) {
                ops.add(new DiffOp(OpType.EQUAL, i, j));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops.add(new DiffOp(OpType.DELETE, i, -1));
                i++;
            } else {
                ops.add(new DiffOp(OpType.INSERT, -1, j));
                j++;
            }
        }
        while (i < m) {
            ops.add(new DiffOp(OpType.DELETE, i, -1));
            i++;
        }
        while (j < n) {
            ops.add(new DiffOp(OpType.INSERT, -1, j));
            j++;
        }
        return ops;
    }

    /**
     * 将编辑操作序列分组为 hunk（每个 hunk 含变更及前后 CONTEXT_LINES 行上下文）。
     */
    private List<Hunk> groupHunks(List<DiffOp> ops) {
        List<Hunk> hunks = new ArrayList<>();

        // 找到所有变更操作的下标
        List<Integer> changeIndices = new ArrayList<>();
        for (int k = 0; k < ops.size(); k++) {
            if (ops.get(k).type != OpType.EQUAL) {
                changeIndices.add(k);
            }
        }
        if (changeIndices.isEmpty()) {
            return hunks;
        }

        // 将相邻变更（间隔 <= 2*CONTEXT_LINES）合并为一个 hunk
        int groupStart = changeIndices.get(0);
        int groupEnd = groupStart;
        for (int k = 1; k < changeIndices.size(); k++) {
            int idx = changeIndices.get(k);
            if (idx - groupEnd <= 2 * CONTEXT_LINES) {
                groupEnd = idx;
            } else {
                hunks.add(buildHunk(ops, groupStart, groupEnd));
                groupStart = idx;
                groupEnd = idx;
            }
        }
        hunks.add(buildHunk(ops, groupStart, groupEnd));

        return hunks;
    }

    /**
     * 构建单个 hunk：截取 [firstChange - CONTEXT, lastChange + CONTEXT] 范围的操作，
     * 生成 @@ -oldStart,oldCount +newStart,newCount @@ 头部。
     */
    private Hunk buildHunk(List<DiffOp> ops, int firstChange, int lastChange) {
        int start = Math.max(0, firstChange - CONTEXT_LINES);
        int end = Math.min(ops.size() - 1, lastChange + CONTEXT_LINES);

        List<DiffOp> hunkOps = new ArrayList<>(ops.subList(start, end + 1));

        // 计算 oldStart / newStart（1-based，0 表示空端）
        int oldStart = 0;
        int newStart = 0;
        for (DiffOp op : hunkOps) {
            if (op.type == OpType.EQUAL || op.type == OpType.DELETE) {
                if (oldStart == 0) {
                    oldStart = op.oldIndex + 1;
                }
            }
            if (op.type == OpType.EQUAL || op.type == OpType.INSERT) {
                if (newStart == 0) {
                    newStart = op.newIndex + 1;
                }
            }
            if (oldStart != 0 && newStart != 0) {
                break;
            }
        }

        // 计算各端行数
        int oldCount = 0;
        int newCount = 0;
        for (DiffOp op : hunkOps) {
            if (op.type == OpType.EQUAL || op.type == OpType.DELETE) {
                oldCount++;
            }
            if (op.type == OpType.EQUAL || op.type == OpType.INSERT) {
                newCount++;
            }
        }

        // count 为 0 时 start 规定为 0（git 惯例）
        if (oldCount == 0) {
            oldStart = 0;
        }
        if (newCount == 0) {
            newStart = 0;
        }

        String header = String.format("@@ -%d,%d +%d,%d @@",
                oldStart, oldCount, newStart, newCount);
        return new Hunk(header, hunkOps);
    }

    // ==================== 辅助方法 ====================

    /**
     * 从项目根路径读取原文件内容。
     *
     * @param projectBasePath 项目本地仓库根路径
     * @param filePath        相对文件路径
     * @return 文件内容字符串；文件不存在或读取失败返回 null
     */
    private String readFile(String projectBasePath, String filePath) {
        if (projectBasePath == null || projectBasePath.isBlank()) {
            return null;
        }
        try {
            Path fullPath = Paths.get(projectBasePath, filePath);
            if (!Files.exists(fullPath)) {
                log.debug("原文件不存在，将回退为 CREATE: {}", fullPath);
                return null;
            }
            return Files.readString(fullPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取原文件失败 {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * 将文本按行分割（保留空行，不保留换行符）。
     */
    private String[] splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return new String[0];
        }
        // 统一换行符后分割
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        // 按行分割，末尾空行不丢弃
        String[] lines = normalized.split("\n", -1);
        // 若末尾因换行符产生空字符串，去掉
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()
                && content.endsWith("\n")) {
            String[] trimmed = new String[lines.length - 1];
            System.arraycopy(lines, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return lines;
    }

    /**
     * 解析 SolutionStep.evidenceIds（JSON 数组字符串）。
     * 简化处理：直接提取引号内的字符串。
     */
    private List<String> parseEvidenceIds(String evidenceIdsJson) {
        if (evidenceIdsJson == null || evidenceIdsJson.isBlank()) {
            return Collections.emptyList();
        }
        // 简化解析：去掉方括号，按逗号分割，去引号去空格
        String trimmed = evidenceIdsJson.trim();
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("]")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = trimmed.split(",");
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String id = part.trim().replaceAll("^\"|\"$", "");
            if (!id.isEmpty()) {
                result.add(id);
            }
        }
        return result;
    }
}
