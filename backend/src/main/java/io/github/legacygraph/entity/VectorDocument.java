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

    /** 项目ID（UUID字符串） */
    private String projectId;

    /** 扫描版本ID */
    private String versionId;

    /** 分片类型：DOC/CODE/DB/UI */
    private String chunkType;

    /** 来源文件URI */
    private String sourceUri;

    /** 来源hash（去重用） */
    private String sourceHash;

    /** 分片索引 */
    private Integer chunkIndex;

    /** 分片文本内容 */
    private String content;

    /** 内容SHA256（去重用） */
    private String contentSha256;

    /** 元数据JSON */
    private String meta;

    /** 使用的embedding模型名 */
    private String embeddingModel;

    /** 向量维度 */
    private Integer embeddingDim;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
