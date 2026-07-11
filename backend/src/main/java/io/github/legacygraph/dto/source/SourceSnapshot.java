package io.github.legacygraph.dto.source;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 源快照（G-01）。
 * <p>
 * 表示某个源在特定时间点的不可变快照，记录内容指纹、存储位置与 ACL 哈希，
 * 用于增量比对（diff）与版本回溯。由 {@code SourceConnector.fetch} 产出。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceSnapshot {

    /** 快照 ID */
    private String id;

    /** 项目 ID */
    private String projectId;

    /** 源类型 */
    private String sourceType;

    /** 源 ID */
    private String sourceId;

    /** 源 URI */
    private String sourceUri;

    /** 内容哈希 */
    private String contentHash;

    /** 父快照 ID */
    private String parentSnapshotId;

    /** 扫描版本 ID */
    private String scanVersionId;

    /** MIME 类型 */
    private String mimeType;

    /** 字节大小 */
    private Long sizeBytes;

    /** ACL 哈希 */
    private String aclHash;

    /** 存储位置 URI */
    private String storageUri;

    /** 状态 */
    private String status;

    /** 创建时间 */
    private String createdAt;
}
