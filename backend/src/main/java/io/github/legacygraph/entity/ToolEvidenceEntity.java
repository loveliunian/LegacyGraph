package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具证据记录实体 —— 从外部工具输出提取的结构化证据。
 * <p>
 * 对应表：lg_tool_evidence
 */
@Data
@TableName("lg_tool_evidence")
public class ToolEvidenceEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 来源工具运行 ID */
    private String toolRunId;

    /** 证据类型 */
    private String evidenceType;

    /** 源文件路径 */
    private String sourcePath;

    /** 符号 qualified name */
    private String symbolQn;

    /** 起始行号 */
    private Integer lineStart;

    /** 结束行号 */
    private Integer lineEnd;

    /** 原文 hash */
    private String contentSha256;

    /** 截断片段 */
    private String excerpt;

    /** 可关联的图谱节点 key */
    private String graphNodeKey;

    /** 关联的 Claim ID */
    private String claimId;

    /** 置信度 */
    private Double confidence;

    /** 隐私级别 */
    private String privacyLevel;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
