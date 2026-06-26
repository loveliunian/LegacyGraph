package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证据表实体
 */
@Data
@TableName("lg_evidence")
public class Evidence {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionId;
    private String evidenceType;
    private String sourcePath;
    private Integer startLine;
    private Integer endLine;
    private String contentHash;
    private String contentExcerpt;
    private String metadata; // JSONB

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
