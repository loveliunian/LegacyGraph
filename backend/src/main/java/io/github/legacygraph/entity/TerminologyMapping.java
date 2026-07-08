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
 * 术语映射实体：中文业务术语 → 英文子词。
 *
 * <p>用于跨语言名称匹配（文档抽取的中文名 vs 代码抽取的英文名）。
 * 原先硬编码在 {@code application.yml} 的 {@code terminology.mappings}，迁入数据库后
 * 可直接增删改术语，无需改配置重新部署；服务层加载全表并建内存缓存加速。</p>
 */
@Data
@TableName(value = "lg_terminology_mapping", autoResultMap = true)
public class TerminologyMapping {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 源术语（如中文业务术语「出金」）。 */
    private String sourceTerm;

    /** 目标英文子词列表（camelCase 拆分后的形式），JSONB 数组。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> targetTerms;

    /** 说明。 */
    private String description;

    /** 状态：ACTIVE 生效 / DISABLED 停用。 */
    private String status;

    /** 排序。 */
    private Integer sortOrder;

    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
