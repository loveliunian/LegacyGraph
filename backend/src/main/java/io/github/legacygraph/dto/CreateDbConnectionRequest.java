package io.github.legacygraph.dto;

import lombok.Data;

@Data
public class CreateDbConnectionRequest {

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
}
