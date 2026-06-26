package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lg_evidence")
public class Evidence {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String evidenceType;

    private String sourceName;

    private String location;

    private String content;

    private String summary;

    private String relatedNodeIds;

    private String createdBy;

    private LocalDateTime createdAt;
}
