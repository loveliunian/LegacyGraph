package io.github.legacygraph.extractors.bpmn;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 流程引擎数据库连接信息。
 * 由 {@link ProcessEngineConfigExtractor} 从目标项目配置文件自动读取。
 * <p>
 * 写入 {@code ScanContext.config} 的 key 为 {@code "processEngine.db"},
 * 供 {@code BpmnEngineDbAdapter} / {@code CustomWorkflowDbAdapter} 使用。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessEngineConnectionInfo {

    /** 引擎类型 */
    private EngineType engineType;

    /** JDBC URL */
    private String jdbcUrl;

    /** 用户名 */
    private String username;

    /** 密码(明文;jasypt 加密值 ENC(...) 会被跳过,此时 password=null 表示不可用) */
    private String password;

    /** 驱动类名 */
    private String driverClassName;

    /** 表前缀(标准 BPMN 引擎通常 "act_") */
    private String tablePrefix;

    /** 是否加密跳过(jasypt ENC(...) 值无法解密时为 true) */
    private boolean encryptedSkipped;

    /** CUSTOM 引擎专用:表名映射(logical → physical, 如 "processDefinition" → "t_flow_definition") */
    private Map<String, String> customTableMapping;

    /** CUSTOM 引擎专用:字段名映射(logical → physical, 如 "processKey" → "proc_key") */
    private Map<String, String> customColumnMapping;

    /**
     * 连接是否可用。
     * engineType 非 null 且 jdbcUrl 非空且未加密跳过时返回 true。
     */
    public boolean isConnectable() {
        return engineType != null
                && jdbcUrl != null && !jdbcUrl.isBlank()
                && !encryptedSkipped;
    }
}
