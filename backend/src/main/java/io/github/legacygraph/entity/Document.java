package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lg_document")
public class Document {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String versionId;

    private String docName;

    private String docType;

    private String fileType;

    private String filePath;

    private Long fileSize;

    private String parseStatus;

    private Integer factCount;

    private String errorMessage;

    private String uploadedBy;

    private LocalDateTime uploadedAt;

    private LocalDateTime parsedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}
