package io.github.legacygraph.service.solution;

import io.github.legacygraph.dto.solution.RepairSuggestion;
import io.github.legacygraph.entity.Solution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方案修复建议顾问服务（G-23）。
 * <p>当方案校验返回 NEEDS_INPUT 时，根据错误类型生成智能修复建议，
 * 帮助用户快速定位并修正方案中的问题步骤。</p>
 *
 * <p>支持的错误类型与建议：
 * <ul>
 *   <li>FILE_NOT_FOUND / 文件不存在 → 建议修正 filePath 指向已存在的文件</li>
 *   <li>SYMBOL_NOT_FOUND / 符号不存在 → 建议使用图谱中已存在的类</li>
 *   <li>HIGH_RISK / 高风险 → 建议新增覆盖高风险节点的 MODIFY 步骤</li>
 *   <li>CODE_SNIPPET / 代码片段 → 建议将代码片段引用的符号加入影响子图</li>
 *   <li>其他 → 通用建议检查方案步骤与项目结构的一致性</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class SolutionRepairAdvisor {

    /** 文件路径提取正则：匹配 "文件路径 xxx 在图谱" 中的路径 */
    private static final Pattern FILE_PATH_PATTERN =
            Pattern.compile("文件路径\\s+(\\S+)\\s+在图谱");

    /** 符号名提取正则：匹配 "符号 xxx（类名 yyy）" */
    private static final Pattern SYMBOL_PATTERN =
            Pattern.compile("符号\\s+(\\S+?)\\s*（类名\\s+(\\S+?)）");

    /** 高风险节点名提取正则：匹配 "覆盖：xxx（" */
    private static final Pattern HIGH_RISK_NODE_PATTERN =
            Pattern.compile("覆盖：(\\S+?)（");

    /** 代码片段名称提取正则：匹配 "名称 xxx 不在" */
    private static final Pattern CODE_SNIPPET_NAME_PATTERN =
            Pattern.compile("名称\\s+(\\S+)\\s+不在");

    /**
     * 根据校验错误列表生成修复建议。
     *
     * @param projectId 项目 ID
     * @param solution  方案实体（含 analysisJson / impactResultJson 等上下文）
     * @param errors    校验错误信息列表（每条为 error code + message 的组合文本）
     * @return 修复建议列表，与错误列表一一对应
     */
    public List<RepairSuggestion> suggest(String projectId, Solution solution, List<String> errors) {
        List<RepairSuggestion> suggestions = new ArrayList<>();
        if (errors == null || errors.isEmpty()) {
            return suggestions;
        }
        for (String error : errors) {
            if (error == null || error.isBlank()) {
                continue;
            }
            RepairSuggestion suggestion = buildSuggestion(error, solution);
            if (suggestion != null) {
                suggestions.add(suggestion);
            }
        }
        log.info("Repair suggestions generated: projectId={}, solutionId={}, errorCount={}, suggestionCount={}",
                projectId, solution != null ? solution.getId() : "null", errors.size(), suggestions.size());
        return suggestions;
    }

    /**
     * 根据单条错误文本构建修复建议。
     * <p>通过关键词匹配判断错误类型，并尽可能从错误信息中提取具体的目标值
     *（文件路径、符号名、节点名等），使建议更具针对性。</p>
     *
     * @param error    错误文本（包含错误码与描述信息）
     * @param solution 方案实体
     * @return 修复建议，如无法识别错误类型则返回通用建议
     */
    private RepairSuggestion buildSuggestion(String error, Solution solution) {
        String errorType;
        String suggestion;
        String actionType;
        String targetField;

        // 文件不存在错误
        if (error.contains("FILE_NOT_FOUND") || error.contains("文件不存在")) {
            errorType = "FILE_NOT_FOUND";
            actionType = "MODIFY";
            targetField = "filePath";
            String filePath = extractFirst(error, FILE_PATH_PATTERN);
            if (filePath != null) {
                suggestion = String.format("修改 filePath 为 %s（请确认文件路径与项目结构一致，或改为 CREATE 步骤新建文件）", filePath);
            } else {
                suggestion = "修改 filePath 为 src/main/java/.../XxxService.java（请确认文件路径与项目结构一致）";
            }
        }
        // 符号不存在错误
        else if (error.contains("SYMBOL_NOT_FOUND") || error.contains("符号不存在")) {
            errorType = "SYMBOL_NOT_FOUND";
            actionType = "MODIFY";
            targetField = "symbolName";
            String[] symbolInfo = extractSymbol(error);
            if (symbolInfo != null) {
                suggestion = String.format("使用现有类 %s（符号 %s 在图谱中不存在，请改为引用已存在的类名）",
                        symbolInfo[1], symbolInfo[0]);
            } else {
                suggestion = "使用现有类 YyyService（请确认 symbolName 引用的类在图谱中已存在）";
            }
        }
        // 高风险节点未覆盖错误
        else if (error.contains("HIGH_RISK") || error.contains("高风险")) {
            errorType = "HIGH_RISK_UNCOVERED";
            actionType = "MODIFY";
            targetField = "steps";
            String nodeName = extractFirst(error, HIGH_RISK_NODE_PATTERN);
            if (nodeName != null) {
                suggestion = String.format("新增覆盖节点 %s 的 MODIFY 步骤（该高风险节点未被任何方案步骤覆盖）", nodeName);
            } else {
                suggestion = "新增覆盖节点 ZzzMapper 的 MODIFY 步骤（确保所有高风险节点被方案步骤覆盖）";
            }
        }
        // 代码片段一致性错误
        else if (error.contains("CODE_SNIPPET") || error.contains("代码片段")) {
            errorType = error.contains("CODE_SNIPPET_EMPTY") ? "CODE_SNIPPET_EMPTY" : "CODE_SNIPPET_INCONSISTENT";
            actionType = "MODIFY";
            targetField = "codeSnippet";
            String name = extractFirst(error, CODE_SNIPPET_NAME_PATTERN);
            if (name != null) {
                suggestion = String.format("将代码片段中引用的 %s 加入影响子图（或从代码片段中移除未在影响分析中的引用）", name);
            } else {
                suggestion = "将代码片段中引用的 WwwService 加入影响子图（确保代码片段引用的符号均在影响子图中）";
            }
        }
        // 其他未知错误类型
        else {
            errorType = "GENERAL";
            actionType = "MODIFY";
            targetField = "";
            suggestion = "请检查方案步骤是否与项目结构一致";
        }

        return RepairSuggestion.builder()
                .errorType(errorType)
                .errorDetail(error)
                .suggestion(suggestion)
                .actionType(actionType)
                .targetField(targetField)
                .build();
    }

    /**
     * 使用正则从错误文本中提取第一个捕获组。
     *
     * @param text    错误文本
     * @param pattern 正则模式
     * @return 第一个捕获组内容，无匹配则返回 null
     */
    private String extractFirst(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 从错误文本中提取符号名和类名。
     *
     * @param text 错误文本
     * @return [0]=符号名, [1]=类名；无匹配则返回 null
     */
    private String[] extractSymbol(String text) {
        Matcher matcher = SYMBOL_PATTERN.matcher(text);
        if (matcher.find()) {
            return new String[]{matcher.group(1), matcher.group(2)};
        }
        return null;
    }
}
