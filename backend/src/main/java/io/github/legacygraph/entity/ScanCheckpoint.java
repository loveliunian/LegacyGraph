package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * L-06: 扫描检查点 — 支持 pause/resume 后从断点恢复。
 * 每完成一个 file/index 写入检查点，pause 后 resume 时跳过已完成文件。
 */
@Data
@TableName("lg_scan_checkpoint")
public class ScanCheckpoint {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String versionId;

    /** 扫描阶段：CODE_SCAN / DB_SCAN / DOC_PARSE / AI_ORCHESTRATION 等 */
    private String phase;

    private Integer lastFileIndex;

    private String lastFilePath;

    private Integer processedFiles;

    private LocalDateTime updatedAt;
}
