package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 源快照实体（G-02）—— 不可变的 SourceSnapshot 父表。
 * <p>
 * 记录某个源在特定时间点的不可变快照，包含内容指纹、存储位置与 ACL 哈希，
 * 用于增量比对（diff）与版本回溯。每次扫描由 SourceConnector 产出一条新记录，
 * 通过 {@link #parentSnapshotId} 串联历史版本链。
 * </p>
 */
@Data
@TableName("lg_source_snapshot")
public class SourceSnapshotEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    /** 源类型（CODE / DOC / DB / RUN / EXTERNAL） */
    private String sourceType;

    /** 源 ID */
    private String sourceId;

    /** 源 URI */
    private String sourceUri;

    /** 内容哈希（SHA-256） */
    private String contentHash;

    /** 父快照 ID，指向上一个快照，构成版本链 */
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

    /** 状态：ACTIVE / SUPERSEDED 等 */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
