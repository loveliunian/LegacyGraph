package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图谱写入意图 outbox 实体。
 */
@Data
@TableName("lg_graph_write_intent")
public class GraphWriteIntentEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String versionId;

    /** 幂等键，唯一索引 */
    private String idempotencyKey;

    /** 来源：SCAN / AI / MANUAL / COMPILER */
    private String source;

    /** GraphWriteIntent JSON */
    private String payloadJson;

    /** 状态：PENDING / RUNNING / SUCCESS / FAILED / RETRYING */
    private String status;

    /** 重试次数 */
    private Integer retryCount;

    /** 截断错误信息 */
    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;
}
