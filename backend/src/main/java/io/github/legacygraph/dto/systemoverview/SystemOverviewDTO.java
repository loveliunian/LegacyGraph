package io.github.legacygraph.dto.systemoverview;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 系统关系总览 — 业务/功能/代码/数据四层关系总览。
 * 对齐 {@code doc/系统关系总览/02-分层映射详解.md} §0.1。
 */
@Data
@Builder
public class SystemOverviewDTO {

    private String projectId;
    private String versionId;

    /** 12 业务域的四层映射 */
    private List<LayerMappingDTO> mappings;

    /** 核心贯穿链路（业务→功能→代码→数据）描述 */
    private List<String> corePaths;

    /** 业务域数量 */
    private int totalDomains;
}
