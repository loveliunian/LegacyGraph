package io.github.legacygraph.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateDbConnectionRequest {

    @NotBlank(message = "连接名称不能为空")
    @Size(max = 100, message = "连接名称长度不能超过100个字符")
    private String connectionName;

    @NotBlank(message = "数据库类型不能为空")
    private String dbType;

    @NotBlank(message = "主机地址不能为空")
    private String host;

    @NotNull(message = "端口不能为空")
    @Positive(message = "端口必须为正数")
    private Integer port;

    @NotBlank(message = "数据库名称不能为空")
    private String databaseName;

    private String schemaName;

    private String username;

    private String password;

    private Boolean readonly;

    private String includeTables;

    private String excludeTables;
}
