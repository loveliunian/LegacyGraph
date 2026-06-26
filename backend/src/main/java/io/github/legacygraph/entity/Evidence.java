package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证据表 - 所有 LLM 输出都必须可追溯到证据
 */
@Data
@TableName("lg_evidence")
public class Evidence {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionId;
    private String evidenceType;       // code/sql/doc/ui/test
    private String sourcePath;
    private Integer startLine;
    private Integer endLine;
    private String contentHash;
    private String contentExcerpt;
    private String metadata; // JSONB
    private String astPath;
    private String sqlHash;
    private Long chunkId;

    private LocalDateTime createdAt;
}
