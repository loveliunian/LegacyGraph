package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.legacygraph.config.MybatisPlusConfig;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字典项实体
 */
@Data
@TableName("lg_sys_dict_item")
public class SysDictItem {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 字典ID
     */
    @TableField(typeHandler = MybatisPlusConfig.StringUuidTypeHandler.class)
    private String dictId;

    /**
     * 字典项值
     */
    private String itemValue;

    /**
     * 字典项标签
     */
    private String itemLabel;

    /**
     * 描述
     */
    private String description;

    /**
     * 排序
     */
    private Integer sortOrder;

    /**
     * 是否默认
     */
    private Boolean isDefault;

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
