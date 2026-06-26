package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 向量文档实体 - pgvector 存储代码/文档分片向量
 */
@Data
@TableName("lg_vector_document")
public class VectorDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private String chunkType;  // code/doc/db/ui
    private String sourceUri;
    private String sourceHash;
    private Integer chunkIndex;
    private String content;
    private String contentSha256;
    private String meta;  // JSONB
    // Note: embedding is handled by Spring AI pgvector, not needed as entity field
    private String embeddingModel;
    private Integer embeddingDim;
    private LocalDateTime createdAt;
}
