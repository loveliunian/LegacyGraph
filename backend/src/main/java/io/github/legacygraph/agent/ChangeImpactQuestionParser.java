package io.github.legacygraph.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变更请求解析器 — 把"加字段/改表"类自然语言解析为结构化输入，供变更影响链路使用。
 * <p>
 * 主逻辑用正则（不依赖 LLM key，保证链路在无 LLM 时也可用）；未来可叠加 LLM 增强
 * （模板 add-column-parse.txt）提升复杂表述的召回。
 * </p>
 * 识别的 changeKind：
 * <ul>
 *   <li>ADD_COLUMN — 给表加字段（对应 P3 补丁生成链路）</li>
 *   <li>MODIFY_COLUMN — 修改字段类型/长度/约束</li>
 *   <li>ADD_API — 新增接口</li>
 *   <li>REFACTOR — 重构</li>
 *   <li>UNKNOWN — 无法识别具体变更目标，调用方应降级到通用检索</li>
 * </ul>
 */
@Slf4j
@Component
public class ChangeImpactQuestionParser {

    /** 匹配 lg_ 前缀的表名 */
    private static final Pattern TABLE_PATTERN = Pattern.compile("(lg_[a-z0-9_]+)", Pattern.CASE_INSENSITIVE);
    /** "加/新增 priority 字段" */
    private static final Pattern ADD_COLUMN_PATTERN = Pattern.compile(
            "(?:加|新增|增加|添加)\\s*(?:一个\\s*)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:字段|列)");
    /** "改 status 字段" / "修改手机号字段长度" / "改 lg_change_task 表的 risk_level 字段长度" */
    private static final Pattern MODIFY_COLUMN_PATTERN = Pattern.compile(
            "(?:改|修改|调整|变更)\\s+.*?(?:字段|列)");
    /** 字段类型 */
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "(VARCHAR\\(\\s*\\d+\\s*\\)|TEXT|INT|INTEGER|BIGINT|BOOLEAN|DATETIME|TIMESTAMP|DECIMAL\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\)|DATE)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_API_PATTERN = Pattern.compile("(?:加|新增|增加).*(?:接口|API)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFACTOR_PATTERN = Pattern.compile("(?:重构|refactor)", Pattern.CASE_INSENSITIVE);

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParsedChangeRequest {
        /** ADD_COLUMN / MODIFY_COLUMN / ADD_API / REFACTOR / UNKNOWN */
        private String changeKind;
        /** 目标表名（可空） */
        private String tableName;
        /** 字段名（可空） */
        private String columnName;
        /** 字段类型（可空，如 VARCHAR(32)） */
        private String columnType;
        /** 原始问题 */
        private String rawQuestion;
    }

    public ParsedChangeRequest parse(String question) {
        if (question == null || question.isBlank()) {
            return ParsedChangeRequest.builder().changeKind("UNKNOWN").rawQuestion(question).build();
        }
        return parseByRegex(question);
    }

    private ParsedChangeRequest parseByRegex(String question) {
        String tableName = null;
        Matcher tm = TABLE_PATTERN.matcher(question);
        if (tm.find()) {
            tableName = tm.group(1).toLowerCase();
        }

        String columnName = null;
        Matcher addCol = ADD_COLUMN_PATTERN.matcher(question);
        boolean isAddColumn = addCol.find();
        if (isAddColumn) {
            columnName = addCol.group(1);
        }

        boolean isModifyColumn = MODIFY_COLUMN_PATTERN.matcher(question).find();

        String columnType = null;
        Matcher ty = TYPE_PATTERN.matcher(question);
        if (ty.find()) {
            columnType = ty.group(1).toUpperCase().replaceAll("\\s+", "");
        }

        String changeKind = "UNKNOWN";
        if (isAddColumn) {
            changeKind = "ADD_COLUMN";
        } else if (isModifyColumn) {
            changeKind = "MODIFY_COLUMN";
        } else if (ADD_API_PATTERN.matcher(question).find()) {
            changeKind = "ADD_API";
        } else if (REFACTOR_PATTERN.matcher(question).find()) {
            changeKind = "REFACTOR";
        }

        log.debug("Parsed change request: kind={}, table={}, column={}, type={}",
                changeKind, tableName, columnName, columnType);

        return ParsedChangeRequest.builder()
                .changeKind(changeKind)
                .tableName(tableName)
                .columnName(columnName)
                .columnType(columnType)
                .rawQuestion(question)
                .build();
    }
}
