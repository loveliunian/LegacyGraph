package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lg_solution_audit")
public class SolutionAudit {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String solutionId;

    private String reviewer;

    private String beforeStatus;

    private String afterStatus;

    private String decision;

    private String comment;

    private LocalDateTime createdAt;
}
