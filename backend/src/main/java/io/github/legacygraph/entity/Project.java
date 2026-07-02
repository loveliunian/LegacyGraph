package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目表实体
 */
@Data
@TableName("lg_project")
public class Project {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectCode;
    private String projectName;
    private String description;
    private String projectType;
    private String techStack; // JSONB
    private String repoUrl;
    private String defaultBranch;
    private String owner;
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Integer deleted;
}
