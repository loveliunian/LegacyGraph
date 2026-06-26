package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lg_test_case")
public class TestCase {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String caseNo;

    private String caseName;

    private String caseType;

    private String featureName;

    private String apiPath;

    private String method;

    private Integer assertionCount;

    private String generateType;

    private String status;

    private String lastRunStatus;

    private LocalDateTime lastRunTime;

    private String relatedNodeIds;

    private String createdBy;

    private LocalDateTime createdAt;
}
