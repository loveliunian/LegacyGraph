package io.github.legacygraph.dto.change;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 补丁草案中的单个文件变更（阶段二-2.2）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftFile {

    /** 文件路径 */
    private String filePath;

    /** 变更类型：CREATE / MODIFY / DELETE */
    private String changeType;

    /** 原始内容（可选，MODIFY 时填充） */
    private String originalContent;

    /** 期望的新内容 */
    private String newContent;

    /** unified diff */
    private String diff;

    /** 支撑此文件变更的证据 ID 列表 */
    private List<String> evidenceIds;

    /** 影响的符号列表（类名/方法名） */
    private List<String> symbolNames;

    /** 校验状态：PENDING / PASS / FAIL */
    private String validationStatus;

    /** 校验信息 */
    private String validationMessage;
}
