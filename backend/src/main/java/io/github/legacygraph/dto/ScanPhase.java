package io.github.legacygraph.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 扫描阶段定义 — 每个扫描环节的名称、taskType、预估权重
 * <p>
 * 用于前端版本详情页展示环节进度（当前环节、待扫描项数、已处理项数、ETA等）。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanPhase {

    /** 数据库中的 taskType，如 DB_DISCOVERY */
    private String taskType;
    /** 前端展示的阶段名称 */
    private String phaseName;
    /** 阶段序号（0-based） */
    private int order;
}
