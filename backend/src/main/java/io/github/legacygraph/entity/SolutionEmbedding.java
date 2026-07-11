package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 方案嵌入索引实体（G-15）。
 * <p>存储 APPROVED 方案的嵌入文本与向量，供相似历史方案检索使用。
 * 状态流转：ACTIVE → ARCHIVED（方案被废弃时归档）。</p>
 */
@Data
@TableName("lg_solution_embedding")
public class SolutionEmbedding {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联的方案 ID（lg_solution.id） */
    private String solutionId;

    /** 项目 ID */
    private String projectId;

    /** 嵌入文本（用于文本相似度匹配） */
    private String embeddingText;

    /** 嵌入向量（预留，简化版暂不使用） */
    private byte[] embedding;

    /** 参考价值计数（被引用为有用的次数） */
    private Integer usefulCount;

    /**
     * 状态：ACTIVE / ARCHIVED
     */
    private String status;

    private LocalDateTime createdAt;
}
