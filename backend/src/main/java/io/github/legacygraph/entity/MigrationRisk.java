package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 迁移风险实体
 */
@Data
@TableName("migration_risk")
public class MigrationRisk {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 关联项目ID
     */
    private String projectId;

    /**
     * 关联扫描版本ID
     */
    private String versionId;

    /**
     * 风险类型
     */
    private String riskType;

    /**
     * 风险名称
     */
    private String riskName;

    /**
     * 风险描述
     */
    private String description;

    /**
     * 受影响的节点ID列表
     */
    private List<String> affectedNodes;

    /**
     * 严重程度: HIGH/MEDIUM/LOW
     */
    private String severity;

    /**
     * 状态: OPEN/IN_PROGRESS/RESOLVED/CLOSED
     */
    private String status;

    /**
     * 缓解措施
     */
    private String mitigation;

    /**
     * 预估工作量（人天）
     */
    private Integer estimatedEffort;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
