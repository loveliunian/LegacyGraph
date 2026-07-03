package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "lg_notifications", autoResultMap = true)
public class Notification {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String eventType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    private Boolean read;

    private LocalDateTime createdAt;
}
