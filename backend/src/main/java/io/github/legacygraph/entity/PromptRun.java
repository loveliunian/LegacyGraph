package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 运行记录实体 - 审计、缓存、重试
 */
@Data
@TableName("lg_prompt_run")
public class PromptRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectId;
    private String taskType;
    private String providerCode;
    private String modelId;
    private String templateCode;
    private String templateVersion;
    private String inputHash;
    private String maskedInput;  // JSONB 脱敏输入
    private String rawOutput;   // JSONB 原始输出
    private String parsedOutput; // JSONB 解析输出
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer latencyMs;
    private String status;  // success/failed/review
    private String createdBy;
    private LocalDateTime createdAt;
}
