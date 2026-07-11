package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * L-03: 文档分片解析失败日志。
 * 记录大文档分片解析失败的详细信息，用于后续重试和治理。
 */
@Data
@TableName("lg_parse_failure")
public class ParseFailure {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String versionId;

    private String documentId;

    private String filePath;

    private Integer shardIndex;

    private Integer shardTotal;

    private Integer charStart;

    private Integer charEnd;

    private String failureType;

    private String errorMessage;

    private LocalDateTime createdAt;
}
