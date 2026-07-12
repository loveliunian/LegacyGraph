package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * S4-T6: QA 评估运行记录实体 — 评估指标落库，支持可查询。
 */
@Data
@TableName("lg_qa_evaluation_run")
public class QaEvaluationRun {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionId;
    private LocalDateTime evaluatedAt;

    /** 核心指标 */
    private Double entityRecall;
    private Double evidencePrecision;
    private Double requiredKeywordCoverage;
    private Double abstentionAccuracy;

    /** Ragas 指标 */
    private Double ragasContextPrecision;
    private Double ragasContextRecall;
    private Double ragasFaithfulness;
    private Double ragasAnswerRelevancy;

    /** 汇总 */
    private Integer totalCases;
    private Integer passedCases;
    private Boolean passed;
    private String failureReasons;
    private String reportFilePath;
    private LocalDateTime createdAt;
}
