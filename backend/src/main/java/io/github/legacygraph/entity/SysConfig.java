package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置实体
 */
@Data
@TableName("lg_sys_config")
public class SysConfig {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 配置键
     */
    private String configKey;

    /**
     * 配置名称
     */
    private String configName;

    /**
     * 配置值
     */
    private String configValue;

    /**
     * 配置类型: STRING/NUMBER/BOOLEAN/JSON
     */
    private String configType;

    /**
     * 描述
     */
    private String description;

    /**
     * 是否系统配置
     */
    private Boolean isSystem;

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
