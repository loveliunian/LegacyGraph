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
 * 文件快照实体 — 记录文件内容 SHA-256 哈希，用于增量扫描判定。
 * <p>
 * 每次扫描完成后更新文件哈希；重扫时对比哈希即可识别变更文件，
 * 仅对变更文件重新执行 ExtractionAdapter 抽取。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("lg_file_snapshot")
public class FileSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectId;

    /** 文件相对路径（相对于项目根目录） */
    private String filePath;

    /** 内容 SHA-256 哈希（十六进制） */
    private String fileHash;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 快照记录时间 */
    private LocalDateTime scannedAt;

    /** L-05: 抽取器版本（变化时触发 LOGIC_RESCAN） */
    private String extractorVersion;
    /** L-05: 嵌入模型名称（变化时触发 LOGIC_RESCAN） */
    private String embeddingModel;
    /** L-05: 图谱本体版本（变化时触发 LOGIC_RESCAN） */
    private String graphOntologyVersion;
    /** L-05: 变更类型 ADDED/MODIFIED/DELETED/RENAMED/LOGIC_RESCAN */
    private String changeType;
    /** L-05: 最后可见时间（RENAMED 后旧节点清退用） */
    private LocalDateTime lastSeenAt;
}
