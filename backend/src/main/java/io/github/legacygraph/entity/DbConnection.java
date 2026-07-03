package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lg_db_connection")
public class DbConnection {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String connectionName;

    private String dbType;

    private String host;

    private Integer port;

    private String databaseName;

    private String schemaName;

    private String username;

    private String password;

    private Boolean readonly;

    private String includeTables;

    private String excludeTables;

    private String status;

    private Integer tableCount;

    private LocalDateTime lastScanTime;

    /** schema 指纹：用于判断是否需要进行增量重扫 */
    private String schemaFingerprint;
    private LocalDateTime schemaFingerprintUpdatedAt;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}
