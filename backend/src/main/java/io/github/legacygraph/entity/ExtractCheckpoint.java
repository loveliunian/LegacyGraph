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
 * 提取断点 — 记录每个文件在 AI 提取阶段的状态，支持大项目中断后断点续传。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("lg_extract_checkpoint")
public class ExtractCheckpoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectId;
    private String versionId;
    private String filePath;
    private String stepName;

    /** PENDING / EXTRACTING / DONE / FAILED */
    private String status;

    private String resultJson;
    private String errorMsg;
    private LocalDateTime extractedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
