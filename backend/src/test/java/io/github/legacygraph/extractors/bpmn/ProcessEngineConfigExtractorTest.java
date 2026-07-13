package io.github.legacygraph.extractors.bpmn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProcessEngineConfigExtractor 单元测试。
 * 用 @TempDir 创建临时目录,写入测试用 application.yml/.properties,验证配置读取逻辑。
 */
class ProcessEngineConfigExtractorTest {

    @TempDir
    Path tempDir;

    private final ProcessEngineConfigExtractor extractor = new ProcessEngineConfigExtractor();

    /** 辅助:在 tempDir 下创建文件并写入内容 */
    private Path writeFile(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void extract_returnsNullForEmptyDir() throws IOException {
        // tempDir 存在但无配置文件
        assertNull(extractor.extract(tempDir.toString()));
    }

    @Test
    void extract_returnsNullWhenNoConfigFile() throws IOException {
        // 有其他文件但无 application 配置
        writeFile("src/Main.java", "public class Main {}");
        assertNull(extractor.extract(tempDir.toString()));
    }

    @Test
    void extract_returnsNullForBlankDir() {
        assertNull(extractor.extract(""));
        assertNull(extractor.extract(null));
    }

    @Test
    void extract_parsesSpringDatasource() throws IOException {
        writeFile("src/main/resources/application.yml", """
                spring:
                  datasource:
                    url: jdbc:mysql://localhost:3306/testdb
                    username: root
                    password: 123456
                    driver-class-name: com.mysql.cj.jdbc.Driver
                """);
        ProcessEngineConnectionInfo info = extractor.extract(tempDir.toString());
        assertNotNull(info);
        assertEquals(EngineType.FLOWABLE, info.getEngineType()); // 默认 FLOWABLE
        assertEquals("jdbc:mysql://localhost:3306/testdb", info.getJdbcUrl());
        assertEquals("root", info.getUsername());
        assertEquals("123456", info.getPassword());
        assertEquals("com.mysql.cj.jdbc.Driver", info.getDriverClassName());
        assertEquals("act_", info.getTablePrefix());
        assertFalse(info.isEncryptedSkipped());
        assertTrue(info.isConnectable());
    }

    @Test
    void extract_parsesFlowableDatasource() throws IOException {
        writeFile("src/main/resources/application.yml", """
                spring:
                  datasource:
                    url: jdbc:mysql://localhost:3306/maindb
                    username: root
                    password: mainpass
                flowable:
                  datasource:
                    url: jdbc:mysql://localhost:3306/flowable_db
                    username: flowable
                    password: flowablepass
                    driver-class-name: com.mysql.cj.jdbc.Driver
                """);
        ProcessEngineConnectionInfo info = extractor.extract(tempDir.toString());
        assertNotNull(info);
        assertEquals(EngineType.FLOWABLE, info.getEngineType());
        // flowable.datasource 优先于 spring.datasource
        assertEquals("jdbc:mysql://localhost:3306/flowable_db", info.getJdbcUrl());
        assertEquals("flowable", info.getUsername());
        assertEquals("flowablepass", info.getPassword());
    }

    @Test
    void extract_parsesActivitiDatasource() throws IOException {
        writeFile("src/main/resources/application.yml", """
                activiti:
                  datasource:
                    url: jdbc:mysql://localhost:3306/activiti_db
                    username: activiti
                    password: activitipass
                """);
        ProcessEngineConnectionInfo info = extractor.extract(tempDir.toString());
        assertNotNull(info);
        assertEquals(EngineType.ACTIVITI, info.getEngineType());
        assertEquals("jdbc:mysql://localhost:3306/activiti_db", info.getJdbcUrl());
    }

    @Test
    void extract_parsesCamundaDatasource() throws IOException {
        writeFile("src/main/resources/application.yml", """
                camunda:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/camunda_db
                    username: camunda
                    password: camundapass
                """);
        ProcessEngineConnectionInfo info = extractor.extract(tempDir.toString());
        assertNotNull(info);
        assertEquals(EngineType.CAMUNDA, info.getEngineType());
        assertEquals("jdbc:postgresql://localhost:5432/camunda_db", info.getJdbcUrl());
    }

    @Test
    void extract_parsesPropertiesFile() throws IOException {
        writeFile("src/main/resources/application.properties",
                "spring.datasource.url=jdbc:mysql://localhost:3306/propdb\\n" +
                "spring.datasource.username=propuser\\n" +
                "spring.datasource.password=proppass\\n" +
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver\\n");
        // 注意:Files.writeString 不会解析 \n 转义,需要用真实换行
        writeFile("src/main/resources/application.properties",
                "spring.datasource.url=jdbc:mysql://localhost:3306/propdb\n" +
                "spring.datasource.username=propuser\n" +
                "spring.datasource.password=proppass\n" +
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver\n");
        ProcessEngineConnectionInfo info = extractor.extract(tempDir.toString());
        assertNotNull(info);
        assertEquals("jdbc:mysql://localhost:3306/propdb", info.getJdbcUrl());
        assertEquals("propuser", info.getUsername());
        assertEquals("proppass", info.getPassword());
    }

    @Test
    void extract_skipsEncryptedPassword() throws IOException {
        writeFile("src/main/resources/application.yml", """
                spring:
                  datasource:
                    url: jdbc:mysql://localhost:3306/testdb
                    username: root
                    password: ENC(abc123encrypted)
                """);
        ProcessEngineConnectionInfo info = extractor.extract(tempDir.toString());
        assertNotNull(info);
        assertTrue(info.isEncryptedSkipped());
        assertNull(info.getPassword());
        assertFalse(info.isConnectable());
    }

    @Test
    void extract_mergesProfileConfig() throws IOException {
        // 基础配置
        writeFile("src/main/resources/application.yml", """
                spring:
                  datasource:
                    url: jdbc:mysql://localhost:3306/devdb
                    username: devuser
                    password: devpass
                """);
        // prod profile 覆盖 url 和 password
        writeFile("src/main/resources/application-prod.yml", """
                spring:
                  datasource:
                    url: jdbc:mysql://prod-host:3306/proddb
                    password: prodpass
                """);
        ProcessEngineConnectionInfo info = extractor.extract(tempDir.toString());
        assertNotNull(info);
        // prod 覆盖基础
        assertEquals("jdbc:mysql://prod-host:3306/proddb", info.getJdbcUrl());
        assertEquals("prodpass", info.getPassword());
        // username 未被 prod 覆盖,保留基础值
        assertEquals("devuser", info.getUsername());
    }

    @Test
    void extract_detectsCustomEngine() throws IOException {
        writeFile("src/main/resources/application.yml", """
                workflow:
                  datasource:
                    url: jdbc:mysql://localhost:3306/workflow_db
                    username: wuser
                    password: wpass
                  tables:
                    processDefinition: t_flow_definition
                    flowNode: t_flow_node
                    sequenceFlow: t_flow_transition
                    runtimeLog: t_flow_log
                  columns:
                    processKey: proc_key
                    processName: proc_name
                    nodeId: node_id
                    nodeName: node_name
                    nodeType: node_type
                    sourceNode: from_node
                    targetNode: to_node
                    condition: condition_expr
                """);
        ProcessEngineConnectionInfo info = extractor.extract(tempDir.toString());
        assertNotNull(info);
        assertEquals(EngineType.CUSTOM, info.getEngineType());
        assertEquals("jdbc:mysql://localhost:3306/workflow_db", info.getJdbcUrl());
        assertNotNull(info.getCustomTableMapping());
        assertEquals("t_flow_definition", info.getCustomTableMapping().get("processDefinition"));
        assertEquals("t_flow_node", info.getCustomTableMapping().get("flowNode"));
        assertNotNull(info.getCustomColumnMapping());
        assertEquals("proc_key", info.getCustomColumnMapping().get("processKey"));
        assertEquals("node_type", info.getCustomColumnMapping().get("nodeType"));
        assertTrue(info.isConnectable());
    }

    @Test
    void extract_excludesTargetDir() throws IOException {
        // 配置文件在 target/ 下,不应被扫描
        writeFile("target/classes/application.yml", """
                spring:
                  datasource:
                    url: jdbc:mysql://localhost:3306/targetdb
                    username: root
                    password: pass
                """);
        assertNull(extractor.extract(tempDir.toString()));
    }

    @Test
    void extract_detectsEngineTypeFromSpringDatasourceWithFlowableConfig() throws IOException {
        // spring.datasource 提供 URL,但有 flowable.* 配置键 → FLOWABLE
        writeFile("src/main/resources/application.yml", """
                spring:
                  datasource:
                    url: jdbc:mysql://localhost:3306/maindb
                    username: root
                    password: pass
                flowable:
                  database-schema-update: true
                  history-level: full
                """);
        ProcessEngineConnectionInfo info = extractor.extract(tempDir.toString());
        assertNotNull(info);
        assertEquals(EngineType.FLOWABLE, info.getEngineType());
        assertEquals("jdbc:mysql://localhost:3306/maindb", info.getJdbcUrl());
    }
}
