package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * G-02: 资料源不可变快照实体。
 * <p>
 * 记录每次扫描时资料源（代码/文档/数据库/运行证据/外部 API）的完整状态快照。
 * 与 {@link FileSnapshot}（文件级哈希，用于增量检测）不同，本表记录资料源级别的
 * SourceDescriptor 元信息快照，用于"扫描时看到了什么"的追溯。
 * </p>
 * <p>
 * 快照写入后不可修改（immutable），仅可查询。如需重新快照需新建扫描版本。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("lg_source_snapshot")
public class SourceSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectId;

    private String versionId;

    /** 资料源类型：CODE / DOC / DB / RUN / EXTERNAL */
    private String sourceType;

    /** SourceDescriptor 序列化 JSON（完整元信息） */
    private String descriptorJson;

    /** 内容哈希 */
    private String contentHash;

    /** 内容大小（字节） */
    private Long contentSize;

    /** 快照时间 */
    private LocalDateTime snapshotTime;

    private LocalDateTime createdAt;
}
