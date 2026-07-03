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
@TableName(value = "lg_evidence_conflict", autoResultMap = true)
public class EvidenceConflict {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String title;
    private String severity;
    private String nodeId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> sourceA;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> sourceB;

    private String aiSuggestion;
    private String context;
    private Boolean resolved;
    private String resolution;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}
