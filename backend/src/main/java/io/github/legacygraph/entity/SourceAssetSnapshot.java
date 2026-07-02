package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资产快照实体 — 记录每次扫描的 SourceAsset 状态，
 * 用于增量扫描判定（hash/mtime/extractorVersion 比较）。
 */
@Data
@TableName("lg_source_asset_snapshot")
public class SourceAssetSnapshot {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String versionId;

    /** 所属仓库 ID（可为空，例如 DB_SCHEMA 类型） */
    private String repoId;

    /** 资产类型：CODE / DOC / CONFIG / SQL / FRONTEND / DB_SCHEMA */
    private String assetKind;

    /** 项目内的相对路径 */
    private String relativePath;

    /** 内容 SHA-256 哈希 */
    private String contentHash;

    /** 文件大小 */
    private Long fileSize;

    /** 文件最后修改时间（毫秒） */
    private Long lastModifiedMs;

    /** 抽取器版本标识 */
    private String extractorVersion;

    /** 扫描状态：PENDING / SKIPPED / SCANNED / FAILED / DELETED */
    private String scanStatus;

    /** 上一版本快照 ID */
    private String previousSnapshotId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
