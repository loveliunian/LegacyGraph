package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档片段表实体
 */
@Data
@TableName("lg_doc_chunk")
public class DocChunk {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionId;
    private String docName;
    private String docPath;
    private Integer chunkIndex;
    private String titlePath;
    private String content;
    private Integer tokenCount;
    private String metadata; // JSONB
    private String embeddingId;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
