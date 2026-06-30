package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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
    private String sourceName;
    private Integer startLine;
    private Integer endLine;
    private String contentHash;
    private String contentExcerpt;
    private String summary;
    private String content;
    private String metadata; // JSONB
    private String astPath;
    private String sqlHash;
    private Long chunkId;
    private String relatedNodeIds;

    /** 位置（前端展示用，由 sourcePath 转换而来） */
    @TableField(exist = false)
    private String location;

    /** 关联节点数（前端展示用，由 relatedNodeIds 计算而来） */
    @TableField(exist = false)
    private Integer relatedNodeCount;

    /** 关联节点ID列表（前端展示用，由 relatedNodeIds 拆分而来） */
    @TableField(exist = false)
    private List<String> relatedNodeIdList;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
