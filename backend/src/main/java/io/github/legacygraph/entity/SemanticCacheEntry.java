package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 语义缓存条目
 */
@Data
@TableName("lg_semantic_cache")
public class SemanticCacheEntry {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 项目 ID */
    private String projectId;

    /** 原始问题 */
    private String question;

    /** LLM 生成的答案 */
    private String answer;

    /** 使用的证据（JSON） */
    private String evidence;

    /** 问题 embedding（pgvector 文本格式 "[0.1,0.2,...]"，用于向量检索） */
    private String questionEmbedding;

    /** 所属图谱发布ID（版本化缓存维度，关联 lg_graph_release.id） */
    private String graphReleaseId;

    /** ACL 主体哈希（版本化缓存维度， principals 排序后 hash） */
    private String aclHash;

    /** 生成答案时的意图分类 */
    private String intent;

    /** 置信度分数（0.0~1.0） */
    private Double confidence;

    /** 相似度分数（查询时填充，非数据库列） */
    @TableField(exist = false)
    private Double similarity;

    /** 命中次数 */
    private Integer hitCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后访问时间 */
    private LocalDateTime lastAccessAt;
}
