package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AgentRun 合约表（见 doc 4.3）— 每次 Agent 调用的 schema 版本 / 证据 / 成本 / 质量 / 自校正记录。
 * 与 {@link PromptRun} 一对一关联（prompt_run_id）。
 */
@Data
@TableName("lg_agent_run")
public class AgentRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String contractId;
    private String projectId;
    private String agentType;
    private String agentName;
    private String inputSchemaVersion;
    private String outputSchemaVersion;
    /** JSON 数组字符串：用到的证据 id 列表 */
    private String usedEvidenceIds;
    /** JSON 数组字符串：被省略的证据及原因 */
    private String omittedBecause;
    /** 0/1 */
    private Integer needsHumanReview;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Double costUsd;
    private Integer retryCount;
    private Integer selfCorrectionCount;
    private Double qualityScore;
    private Long promptRunId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** JSON 字符串 */
    private String metadata;
    private LocalDateTime createdAt;
}
