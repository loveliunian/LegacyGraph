package io.github.legacygraph.dto.systemoverview;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单业务域的四层映射（业务↔功能↔代码↔数据）。
 * 对齐 {@code doc/系统关系总览/02-分层映射详解.md} 每个业务域小节。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LayerMappingDTO {

    /** 业务域 */
    private String businessDomain;

    /** 业务能力 */
    private String capability;

    /** 功能（前端页面） */
    private String feature;

    /** Controller 类名 */
    private String controller;

    /** API 基路径 */
    private String apiPath;

    /** 代码模块（Service/Agent/Builder） */
    private String codeModule;

    /** 数据表列表 */
    private List<String> dataTables;

    /** 主要边类型 */
    private String edgeType;
}
