package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.legacygraph.config.MybatisPlusConfig;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字典类型实体
 */
@Data
@TableName("sys_dict")
public class SysDict {

    @TableId(type = IdType.ASSIGN_UUID)
    @TableField(typeHandler = MybatisPlusConfig.StringUuidTypeHandler.class)
    private String id;

    /**
     * 字典编码
     */
    private String dictCode;

    /**
     * 字典名称
     */
    private String dictName;

    /**
     * 描述
     */
    private String description;

    /**
     * 排序
     */
    private Integer sortOrder;

    /**
     * 状态: ACTIVE/DISABLED
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
