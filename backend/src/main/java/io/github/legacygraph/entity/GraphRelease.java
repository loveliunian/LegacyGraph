package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 图谱发布实体。
 * <p>
 * 记录某项目某扫描版本的图谱发布生命周期，状态机流转：
 * {@code DRAFT → VALIDATING → PUBLISHED | FAILED}
 * </p>
 * <p>
 * 通过 {@code (project_id, scan_version_id)} 唯一索引保证同一项目同一版本只有一条发布记录，
 * 实现 {@code startValidation} 的幂等性。
 * </p>
 */
@Data
@TableName(value = "lg_graph_release", autoResultMap = true)
public class GraphRelease {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String scanVersionId;

    /** 图谱版本标签（如 v1.0.0），用于发布后引用 */
    private String graphVersionTag;

    /** 状态：DRAFT / VALIDATING / PUBLISHED / FAILED */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime publishedAt;

    /** 失败原因列表（JSONB 列存储，通过 JacksonTypeHandler 序列化） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> failureReasons;

    /** 质量指标 JSON（GraphifyQualityResult 等），发布时写入 */
    private String metrics;
}
