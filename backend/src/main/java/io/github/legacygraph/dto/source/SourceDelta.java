package io.github.legacygraph.dto.source;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 源增量（G-01）。
 * <p>
 * 表示两个快照之间的变更集合，包含新增、修改、删除与重命名条目。
 * 由 {@code SourceConnector.diff} 产出，驱动增量扫描与图谱更新。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceDelta {

    /** 新增的源描述符列表 */
    private List<SourceDescriptor> added;

    /** 修改的源描述符列表 */
    private List<SourceDescriptor> modified;

    /** 删除的源描述符列表 */
    private List<SourceDescriptor> deleted;

    /** 重命名条目列表 */
    private List<RenameEntry> renamed;

    /**
     * 重命名条目：记录路径变更前后的映射及其描述符。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RenameEntry {

        /** 变更前路径 */
        private String beforePath;

        /** 变更后路径 */
        private String afterPath;

        /** 关联的源描述符 */
        private SourceDescriptor descriptor;
    }
}
