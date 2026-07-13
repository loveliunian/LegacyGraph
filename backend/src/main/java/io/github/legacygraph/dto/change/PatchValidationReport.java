package io.github.legacygraph.dto.change;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 补丁草案校验报告（阶段二-2.2）。
 * <p>
 * 三类校验结果：范围校验（文件属于影响子图）、格式校验（unified diff）、证据校验。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatchValidationReport {

    /** 是否通过全部校验 */
    private boolean passed;

    /** 范围校验错误列表 */
    private List<String> scopeErrors;

    /** 格式校验错误列表 */
    private List<String> formatErrors;

    /** 证据校验错误列表 */
    private List<String> evidenceErrors;

    /** 已校验的文件数量 */
    private int totalFiles;

    /** 通过校验的文件数量 */
    private int passedFiles;

    /** 整体风险等级：LOW / MEDIUM / HIGH */
    private String riskLevel;
}
