# BPMN 流程引擎 DB 扫描实施补充计划(步骤 8-15)

## 摘要

本计划是 [bpmn-process-engine-scan-support.md](file:///Users/huymac/工作/数智/LegacyGraph/.trae/documents/bpmn-process-engine-scan-support.md) 的补充,聚焦原计划 15 步实施顺序中**剩余的步骤 8-15**。

步骤 1-7(文件源闭环)已在前一会话完成:依赖引入、NodeType/EdgeType 扩展、资产发现补全、BpmnModelParser、BpmnFileAdapter、GraphBuilder.buildBpmnProcessGraph、文件源单元测试均已就绪并通过。

本补充计划解决**数据库源 + 运行时分析 + 编排集成**三大块,覆盖:
- 从目标项目 `application.yml` 自动读取流程引擎 DB 连接信息
- 连接标准 BPMN 引擎库(act_ 前缀)读取已部署流程定义 + 运行时数据
- 连接自研流程引擎库(配置驱动表名映射)
- 在 ProjectScanner 的 ADAPTER_SCAN 与 EXTERNAL_VERIFY 之间插入流程引擎 DB 扫描阶段
- 失败隔离:DB 扫描失败不阻塞后续 AI_ORCHESTRATION

## 当前状态确认(Phase 1 探索结论)

### 已完成(步骤 1-7)

| 步骤 | 文件 | 状态 |
|------|------|------|
| 1 | [pom.xml](file:///Users/huymac/工作/数智/LegacyGraph/backend/pom.xml#L37-L38) — camunda-bpmn-model 7.24.0 | ✅ |
| 2 | [NodeType.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/common/NodeType.java) — ProcessDefinition/UserTask/ServiceTask/Gateway | ✅ |
| 2 | [EdgeType.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/common/EdgeType.java) — FLOW_TO/RUNTIME_FLOW_TO/HAS_FLOW_NODE/EXECUTES_BY/LISTENED_BY/DEPLOYED_TO | ✅ |
| 3 | [AssetDiscoveryService.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/AssetDiscoveryService.java) — `.bpmn`/`.bpmn20.xml` 扩展名 | ✅ |
| 3 | [ProjectScanner.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java) — isAdapterCandidate | ✅ |
| 3 | [IncrementalScanService.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/service/IncrementalScanService.java#L41-L43) — TRACKED_EXTENSIONS | ✅ |
| 4 | [BpmnProcessFact.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/extractors/bpmn/BpmnProcessFact.java) — 中间表示模型(含运行时增强字段) | ✅ |
| 4 | [BpmnModelParser.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/extractors/bpmn/BpmnModelParser.java) — parseFromFile + parseFromStream | ✅ |
| 5 | [BpmnFileAdapter.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/extractors/adapter/BpmnFileAdapter.java) — 文件源适配器 | ✅ |
| 6 | [GraphBuilder.java#L3906](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/builder/GraphBuilder.java#L3906) — buildBpmnProcessGraph | ✅ |
| 7 | BpmnModelParserTest + BpmnFileAdapterTest — 单元测试通过 | ✅ |
| - | [EngineType.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/extractors/bpmn/EngineType.java) — FLOWABLE/ACTIVITI/CAMUNDA/CUSTOM | ✅ |

### 关键架构锚点(本期依赖)

- **ScanContext**: [ScanContext.java#L35](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/extractors/adapter/ScanContext.java#L35) 有 `Map<String, Object> config` 字段,用于携带 `processEngine.db` 连接信息
- **ScanContext 构造点**: [ProjectScanner.java#L2177](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java#L2177) `scanAssetsWithAdapters` 中已构造带 `config(ConcurrentHashMap)` 的 context
- **backendDir 推导**: [ProjectScanner.java#L635-L651](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java#L635-L651) 从 baseDir/repo 推导出 backendDir,可直接传给 ProcessEngineConfigExtractor
- **ADAPTER_SCAN 阶段**: [ProjectScanner.java#L785-L834](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java#L785-L834)
- **MAPPER_SQL_LINK 后置连接**(DB 扫描插入点之前): [ProjectScanner.java#L874-L887](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java#L874-L887)
- **EXTERNAL_VERIFY 阶段**(DB 扫描插入点之后): [ProjectScanner.java#L889](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java#L889)
- **ProjectScanner 注入风格**: 必选依赖构造器注入([L358-L395](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java#L358-L395)),可选依赖 `@Autowired(required=false)` setter 注入([L100-L161](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java#L100-L161))
- **snakeyaml**: spring-boot-starter 传递依赖,可直接使用 `org.yaml.snakeyaml.Yaml`,无需显式引入
- **jasypt**: 项目无 jasypt 依赖,ENC(...) 检测只需正则匹配跳过
- **H2 测试**: 参考 [BpmnFileAdapterTest](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/test/java/io/github/legacygraph/extractors/adapter/BpmnFileAdapterTest.java) 的 `@ExtendWith(MockitoExtension.class)` 纯 Mockito 模式,不依赖 Spring Context

## 待完成步骤详细实施

### 步骤 8: ProcessEngineConnectionInfo + ProcessEngineConfigExtractor

#### 8.1 新建 `ProcessEngineConnectionInfo.java`

**文件**: `backend/src/main/java/io/github/legacygraph/extractors/bpmn/ProcessEngineConnectionInfo.java`

```java
package io.github.legacygraph.extractors.bpmn;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

/**
 * 流程引擎数据库连接信息。
 * 由 ProcessEngineConfigExtractor 从目标项目配置文件自动读取。
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
    /** 密码 (明文;加密值 ENC(...) 会被跳过,此时 password=null 表示不可用) */
    private String password;
    /** 驱动类名 */
    private String driverClassName;
    /** 表前缀 (标准 BPMN 引擎通常 "act_") */
    private String tablePrefix;
    /** 是否加密跳过 (jasypt ENC(...) 值无法解密时为 true) */
    private boolean encryptedSkipped;
    /** CUSTOM 引擎专用:表名映射 */
    private Map<String, String> customTableMapping;
    /** CUSTOM 引擎专用:字段名映射 */
    private Map<String, String> customColumnMapping;

    /** 连接是否可用 (engineType 非 null 且 jdbcUrl 非 null 且未加密跳过) */
    public boolean isConnectable() {
        return engineType != null
                && jdbcUrl != null && !jdbcUrl.isBlank()
                && !encryptedSkipped;
    }
}
```

#### 8.2 新建 `ProcessEngineConfigExtractor.java`

**文件**: `backend/src/main/java/io/github/legacygraph/extractors/bpmn/ProcessEngineConfigExtractor.java`

**职责**: 扫描目标项目 `backendDir` 下的 `application*.yml`/`application*.properties`,按优先级解析流程引擎数据源,识别引擎类型,处理 jasypt 加密跳过。

**配置读取优先级**(从高到低):
1. `flowable.datasource.*` — Flowable 独立数据源
2. `activiti.datasource.*` — Activiti 独立数据源
3. `camunda.datasource.*` — Camunda 独立数据源
4. `spring.datasource.*` — 复用主数据源(最常见,引擎表与业务表同库)

**引擎类型识别**:
- 存在 `flowable.*` 配置键 → FLOWABLE
- 存在 `activiti.*` 配置键 → ACTIVITI
- 存在 `camunda.*` 配置键 → CAMUNDA
- 存在 `workflow.tables.*` 配置 → CUSTOM(自研引擎)
- 以上都没有但 `spring.datasource` 可用 → 默认 FLOWABLE(因 act_ 表前缀最普遍,Flowable/Activiti/Camunda 共用)

**关键设计**:
```java
@Slf4j
@Component
public class ProcessEngineConfigExtractor {

    private static final Pattern ENC_PATTERN = Pattern.compile("ENC\\(.*\\)");

    /**
     * 从目标项目配置文件提取流程引擎 DB 连接信息。
     * @param backendDir 目标项目后端代码根目录
     * @return 连接信息;无配置返回 null
     */
    public ProcessEngineConnectionInfo extract(String backendDir) {
        if (backendDir == null || backendDir.isBlank()) return null;

        List<Path> configFiles = findConfigFiles(backendDir);
        if (configFiles.isEmpty()) return null;

        // 按优先级合并配置: application.yml(基础) → application-{profile}.yml(覆盖)
        Map<String, String> flatProps = loadAndMergeConfigs(configFiles);
        if (flatProps.isEmpty()) return null;

        // 1. 识别 CUSTOM 引擎(workflow.tables.* 存在)
        if (flatProps.keySet().stream().anyMatch(k -> k.startsWith("workflow.tables."))) {
            return buildCustomConnInfo(flatProps);
        }

        // 2. 按优先级查找流程引擎数据源
        String[] prefixes = {"flowable.datasource.", "activiti.datasource.", "camunda.datasource.", "spring.datasource."};
        for (String prefix : prefixes) {
            String url = flatProps.get(prefix + "url");
            if (url != null && !url.isBlank()) {
                return buildStandardConnInfo(flatProps, prefix, detectEngineType(flatProps, prefix));
            }
        }
        return null;
    }

    private EngineType detectEngineType(Map<String, String> props, String matchedPrefix) {
        if (matchedPrefix.startsWith("flowable.")) return EngineType.FLOWABLE;
        if (matchedPrefix.startsWith("activiti.")) return EngineType.ACTIVITI;
        if (matchedPrefix.startsWith("camunda.")) return EngineType.CAMUNDA;
        // spring.datasource 复用主库:按其他配置键推断
        if (props.keySet().stream().anyMatch(k -> k.startsWith("flowable."))) return EngineType.FLOWABLE;
        if (props.keySet().stream().anyMatch(k -> k.startsWith("activiti."))) return EngineType.ACTIVITI;
        if (props.keySet().stream().anyMatch(k -> k.startsWith("camunda."))) return EngineType.CAMUNDA;
        return EngineType.FLOWABLE; // 默认假设 Flowable(act_ 表前缀最普遍)
    }

    private ProcessEngineConnectionInfo buildStandardConnInfo(Map<String, String> props, String prefix, EngineType type) {
        String password = props.get(prefix + "password");
        boolean encSkipped = password != null && ENC_PATTERN.matcher(password).matches();
        if (encSkipped) {
            log.warn("Flowable/Activiti/Camunda datasource password is jasypt-encrypted (ENC(...)), " +
                     "skipping BPMN DB scan. Consider configuring plaintext password for LegacyGraph scan.");
        }
        return ProcessEngineConnectionInfo.builder()
                .engineType(type)
                .jdbcUrl(props.get(prefix + "url"))
                .username(props.get(prefix + "username"))
                .password(encSkipped ? null : password)
                .driverClassName(props.get(prefix + "driver-class-name"))
                .tablePrefix("act_") // 标准 BPMN 引擎固定前缀
                .encryptedSkipped(encSkipped)
                .build();
    }

    private ProcessEngineConnectionInfo buildCustomConnInfo(Map<String, String> props) {
        // 解析 workflow.datasource.* + workflow.tables.* + workflow.columns.*
        // 表映射: processDefinition/flowNode/sequenceFlow/runtimeLog
        // 字段映射: processKey/processName/nodeId/nodeName/nodeType/sourceNode/targetNode/condition
        // nodeType 值映射约定: 1=UserTask, 2=ServiceTask, 3=Gateway (在 CustomWorkflowDbAdapter 中处理)
    }

    /** 查找配置文件: backendDir 下递归找 application*.yml / application*.properties (排除 target/) */
    private List<Path> findConfigFiles(String backendDir) {
        // 用 Files.walk + 过滤,排除 /target/ /build/ /node_modules/
        // 匹配 application.yml / application.properties / application-{profile}.yml/.properties
    }

    /** 加载并合并配置: .yml 用 snakeyaml, .properties 用 Properties 类,扁平化为 key→value */
    private Map<String, String> loadAndMergeConfigs(List<Path> files) {
        // 1. 按优先级排序: application.yml/properties 在前, application-{profile} 在后
        // 2. 逐个加载,扁平化 key(如 spring.datasource.url → "spring.datasource.url")
        // 3. 后加载的覆盖先加载的
    }
}
```

**snakeyaml 用法**:
```java
Yaml yaml = new Yaml();
Map<String, Object> loaded = yaml.loadAs(new FileReader(file), Map.class);
// 递归扁平化嵌套 Map: spring → datasource → url → "spring.datasource.url"
```

### 步骤 9: ProcessEngineConfigExtractorTest

**文件**: `backend/src/test/java/io/github/legacygraph/extractors/bpmn/ProcessEngineConfigExtractorTest.java`

**测试模式**: 纯单元测试,不依赖 Spring Context。用 `@TempDir` 创建临时目录,写入测试用 `application.yml`/`.properties`。

**测试用例**:

| 测试方法 | 场景 | 验证 |
|---------|------|------|
| `extract_returnsNullForEmptyDir` | 空目录 | 返回 null |
| `extract_returnsNullWhenNoConfigFile` | 无 application.yml | 返回 null |
| `extract_parsesSpringDatasource` | application.yml 含 `spring.datasource.url/user/password` | engineType=FLOWABLE(默认), jdbcUrl 正确, tablePrefix="act_" |
| `extract_parsesFlowableDatasource` | application.yml 含 `flowable.datasource.url` (优先于 spring.datasource) | engineType=FLOWABLE, jdbcUrl 来自 flowable 段 |
| `extract_parsesActivitiDatasource` | application.yml 含 `activiti.datasource.*` | engineType=ACTIVITI |
| `extract_parsesCamundaDatasource` | application.yml 含 `camunda.datasource.*` | engineType=CAMUNDA |
| `extract_parsesPropertiesFile` | application.properties 含 `spring.datasource.url=...` | 正确解析 .properties 格式 |
| `extract_skipsEncryptedPassword` | password 值为 `ENC(xxx)` | encryptedSkipped=true, password=null, isConnectable()=false |
| `extract_mergesProfileConfig` | application.yml + application-prod.yml | prod 配置覆盖基础配置 |
| `extract_detectsCustomEngine` | application.yml 含 `workflow.tables.processDefinition=t_flow_def` | engineType=CUSTOM, customTableMapping 非空 |
| `extract_excludesTargetDir` | 配置文件在 `/target/` 下 | 不被扫描 |

**测试 yml fixture 示例**(用 @TempDir 写入):
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/testdb
    username: root
    password: 123456
    driver-class-name: com.mysql.cj.jdbc.Driver
flowable:
  database-schema-update: true
```

### 步骤 10: BpmnEngineDbAdapter(标准 BPMN DB 源)

**文件**: `backend/src/main/java/io/github/legacygraph/extractors/adapter/BpmnEngineDbAdapter.java`

**职责**: 连接标准 BPMN 引擎库(act_ 前缀),读取已部署的流程定义(BPMN XML) + 运行时数据(节点频次/时长/路径),调用 BpmnModelParser + ProcessRuntimeAnalyzer + GraphBuilder 构建图谱。

**关键设计**:

```java
@Slf4j
@Component
public class BpmnEngineDbAdapter implements ExtractionAdapter {

    private final BpmnModelParser parser;
    private final ProcessRuntimeAnalyzer runtimeAnalyzer;
    private final GraphBuilder graphBuilder;

    public BpmnEngineDbAdapter(BpmnModelParser parser,
                                ProcessRuntimeAnalyzer runtimeAnalyzer,
                                GraphBuilder graphBuilder) {
        this.parser = parser;
        this.runtimeAnalyzer = runtimeAnalyzer;
        this.graphBuilder = graphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        return false; // 不通过 SourceAsset 触发,由 ProjectScanner 主动调用 scanFromDatabase
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        return ExtractionResult.builder().processedAssets(0).build();
    }

    /**
     * 从流程引擎数据库扫描已部署的流程定义 + 运行时数据。
     * 由 ProjectScanner 在 ADAPTER_SCAN 完成后主动调用。
     * 失败不阻塞,仅 log.warn。
     */
    public ExtractionResult scanFromDatabase(ScanContext context) {
        ProcessEngineConnectionInfo connInfo =
                (ProcessEngineConnectionInfo) context.getConfig().get("processEngine.db");
        if (connInfo == null || !connInfo.isConnectable()
                || connInfo.getEngineType() == EngineType.CUSTOM) {
            return ExtractionResult.builder().processedAssets(0).summary("BPMN DB scan skipped").build();
        }

        int totalNodes = 0, totalEdges = 0, processCount = 0;
        try (Connection conn = createConnection(connInfo)) {
            // 1. 读取已部署的流程定义 + BPMN XML
            List<BpmnProcessFact> facts = readDeployedProcessDefinitions(conn, connInfo.getTablePrefix());
            processCount = facts.size();

            // 2. 运行时数据增强(节点频次/时长/驳回率/路径频次)
            runtimeAnalyzer.enrichWithRuntimeData(facts, conn, connInfo.getTablePrefix());

            // 3. 构建图谱
            for (BpmnProcessFact fact : facts) {
                graphBuilder.buildBpmnProcessGraph(
                        context.getProjectId(), context.getVersionId(), fact);
                totalNodes += fact.getNodes().size() + 1;
                totalEdges += fact.getFlows().size() + fact.getNodes().size();
            }

            // 4. 构建运行时流转边 RUNTIME_FLOW_TO (动态流转/驳回路径)
            int runtimeEdges = runtimeAnalyzer.buildRuntimeFlowEdges(
                    context.getProjectId(), context.getVersionId(),
                    conn, connInfo.getTablePrefix(), graphBuilder);
            totalEdges += runtimeEdges;

            log.info("BPMN DB scan completed: {} processes, {} nodes, {} edges",
                    processCount, totalNodes, totalEdges);
            return ExtractionResult.builder()
                    .processedAssets(processCount)
                    .nodeCount(totalNodes)
                    .edgeCount(totalEdges)
                    .summary("Scanned " + processCount + " BPMN processes from DB")
                    .build();
        } catch (Exception e) {
            log.warn("BPMN DB scan failed (non-blocking): {}", e.getMessage());
            return ExtractionResult.builder()
                    .processedAssets(0)
                    .summary("BPMN DB scan failed: " + e.getMessage())
                    .build();
        }
    }

    private Connection createConnection(ProcessEngineConnectionInfo connInfo) throws SQLException, ClassNotFoundException {
        if (connInfo.getDriverClassName() != null && !connInfo.getDriverClassName().isBlank()) {
            Class.forName(connInfo.getDriverClassName());
        }
        return DriverManager.getConnection(
                connInfo.getJdbcUrl(),
                connInfo.getUsername(),
                connInfo.getPassword());
    }

    /**
     * 读取已部署的流程定义及对应的 BPMN XML 二进制。
     * SQL (参数化 tablePrefix, 大写因为 Flowable/Activiti 表名大小写因数据库而异,
     *      实际运行时按 ResultSet 元数据做大小写不敏感读取):
     */
    private List<BpmnProcessFact> readDeployedProcessDefinitions(Connection conn, String prefix) throws SQLException {
        String sql = "SELECT p.ID_, p.KEY_, p.NAME_, p.VERSION_, p.DEPLOYMENT_ID_, b.BYTES_ " +
                     "FROM " + prefix + "RE_PROCDEF p " +
                     "JOIN " + prefix + "GE_BYTEARRAY b ON p.DEPLOYMENT_ID_ = b.DEPLOYMENT_ID_ " +
                     "WHERE b.NAME_ LIKE '%.bpmn' OR b.NAME_ LIKE '%.bpmn20.xml'";

        List<BpmnProcessFact> facts = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String procDefId = rs.getString("ID_");
                String procKey = rs.getString("KEY_");
                String procName = rs.getString("NAME_");
                int version = rs.getInt("VERSION_");
                String deploymentId = rs.getString("DEPLOYMENT_ID_");
                byte[] bytes = rs.getBytes("BYTES_");

                if (bytes == null || bytes.length == 0) continue;
                BpmnProcessFact fact = parser.parseFromStream(
                        new ByteArrayInputStream(bytes), "db:" + procDefId);
                if (fact == null) continue;
                fact.setVersion(version);
                fact.setDeploymentId(deploymentId);
                fact.setProcessKey(procKey != null ? procKey : fact.getProcessKey());
                if (procName != null && !procName.isBlank()) fact.setProcessName(procName);
                facts.add(fact);
            }
        }
        return facts;
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("BpmnEngineDbAdapter")
                .languages(Set.of("sql"))
                .fileTypes(Set.of())
                .frameworks(Set.of("flowable", "activiti", "camunda"))
                .aiEnhanced(false)
                .priority(66)
                .build();
    }
}
```

**ResultSet 列名大小写处理**: 不同数据库(H2/MySQL/PostgreSQL/Oracle)对 `ID_`/`id_` 大小写处理不同,统一用 `ResultSetMetaData` 做大小写不敏感查找,提取辅助方法 `getStringCI(rs, "ID_")`。

### 步骤 11: ProcessRuntimeAnalyzer(运行时分析)

**文件**: `backend/src/main/java/io/github/legacygraph/extractors/bpmn/ProcessRuntimeAnalyzer.java`

**职责**: 查询 `act_hi_taskinst`/`act_hi_procinst` 历史表,为 BpmnProcessFact 填充运行时属性(execCount/avgDurationMs/rejectRate/flowCount),并构建 RUNTIME_FLOW_TO 边。

**关键设计**:

```java
@Slf4j
@Component
public class ProcessRuntimeAnalyzer {

    /** 运行时查询时间范围(最近 90 天),避免全量历史数据 OOM */
    private static final int RECENT_DAYS = 90;
    /** 路径频次查询最大记录数 */
    private static final int MAX_HISTORY_ROWS = 10000;

    /**
     * 用运行时数据增强 BpmnProcessFact。
     * 修改 fact 中每个 FlowNodeFact 的 execCount/avgDurationMs/rejectRate,
     * 修改每个 SequenceFlowFact 的 flowCount。
     */
    public void enrichWithRuntimeData(List<BpmnProcessFact> facts, Connection conn, String prefix) {
        if (facts.isEmpty()) return;
        try {
            // 1. 节点执行频次: SELECT TASK_DEF_KEY_, COUNT(*) FROM {p}HI_TASKINST
            //    WHERE START_TIME_ > {now-90d} GROUP BY TASK_DEF_KEY_
            Map<String, Long> execCounts = queryNodeExecCounts(conn, prefix);
            // 2. 节点平均时长: SELECT TASK_DEF_KEY_, AVG(TIMESTAMPDIFF) WHERE END_TIME_ IS NOT NULL
            Map<String, Long> avgDurations = queryNodeAvgDurations(conn, prefix);
            // 3. 驳回率: 同一 PROC_INST_ID_ 中 TASK_DEF_KEY_ 重复出现次数 / 总执行次数
            Map<String, Double> rejectRates = queryNodeRejectRates(conn, prefix);
            // 4. 路径频次: 按 PROC_INST_ID_ 分组, START_TIME_ 排序, 相邻 TASK_DEF_KEY_ 迁移计数
            Map<String, Long> flowCounts = queryPathFlowCounts(conn, prefix);

            // 填充到 facts
            Map<String, BpmnProcessFact.FlowNodeFact> nodeByKey = new HashMap<>();
            for (var fact : facts) {
                for (var node : fact.getNodes()) {
                    String key = fact.getProcessKey() + "." + node.getBpmnId();
                    nodeByKey.put(key, node);
                }
            }
            for (var entry : execCounts.entrySet()) {
                var node = nodeByKey.get(entry.getKey());
                if (node != null) node.setExecCount(entry.getValue());
            }
            // ...同理填充 avgDuration/rejectRate/flowCount
        } catch (Exception e) {
            log.warn("Runtime data enrichment failed (non-blocking): {}", e.getMessage());
        }
    }

    /**
     * 构建 RUNTIME_FLOW_TO 边: 历史数据中实际发生的流转。
     * 用于发现定义里没有但实际发生的路径、高频/低频路径、瓶颈节点。
     */
    public int buildRuntimeFlowEdges(String projectId, String versionId,
                                      Connection conn, String prefix, GraphBuilder graphBuilder) {
        // 1. 查 act_hi_taskinst 按 PROC_INST_ID_ 分组, 按 START_TIME_ 排序, 提取相邻节点迁移
        // 2. 对每条迁移:
        //    - 查 FLOW_TO 边是否已存在(按 from/to 节点)
        //    - 若存在: 增强 flowCount 属性(可选, 本次先不实现增强, 仅建新边)
        //    - 若不存在: 新建 RUNTIME_FLOW_TO 边, confidence=0.85, status=PENDING_CONFIRM
        // 3. 返回新建边数
    }

    /** TIMESTAMPDIFF 跨数据库兼容: H2 用 DATEDIFF('SECOND', a, b); MySQL 用 TIMESTAMPDIFF(SECOND, a, b) */
    private String durationExpr(Connection conn) throws SQLException {
        String dbName = conn.getMetaData().getDatabaseProductName();
        if ("H2".equalsIgnoreCase(dbName)) {
            return "DATEDIFF('SECOND', START_TIME_, END_TIME_)";
        }
        return "TIMESTAMPDIFF(SECOND, START_TIME_, END_TIME_)";
    }
}
```

**防 OOM 措施**(参考 project_memory 教训):
- 历史查询加 `AND START_TIME_ > DATEADD(DAY, -90, CURRENT_TIMESTAMP)` 时间范围过滤
- 加 `LIMIT 10000` 限制最大行数
- 聚合查询用 GROUP BY 在数据库侧完成,不拉全表到内存

### 步骤 12: BpmnEngineDbAdapterTest(H2 内存库)

**文件**: `backend/src/test/java/io/github/legacygraph/extractors/adapter/BpmnEngineDbAdapterTest.java`

**测试模式**: 纯 JDBC + H2 内存库 + Mockito mock GraphBuilder。不依赖 Spring Context。

**H2 建表脚本**(测试内联,不依赖 schema-h2.sql):
```sql
CREATE TABLE act_re_procdef (
    ID_ VARCHAR(64) PRIMARY KEY,
    KEY_ VARCHAR(255),
    NAME_ VARCHAR(255),
    VERSION_ INT,
    DEPLOYMENT_ID_ VARCHAR(64)
);
CREATE TABLE act_ge_bytearray (
    ID_ VARCHAR(64) PRIMARY KEY,
    DEPLOYMENT_ID_ VARCHAR(64),
    NAME_ VARCHAR(255),
    BYTES_ BLOB
);
CREATE TABLE act_hi_taskinst (
    ID_ VARCHAR(64) PRIMARY KEY,
    PROC_DEF_ID_ VARCHAR(64),
    PROC_INST_ID_ VARCHAR(64),
    TASK_DEF_KEY_ VARCHAR(255),
    NAME_ VARCHAR(255),
    START_TIME_ TIMESTAMP,
    END_TIME_ TIMESTAMP
);
CREATE TABLE act_hi_procinst (
    ID_ VARCHAR(64) PRIMARY KEY,
    PROC_DEF_ID_ VARCHAR(64),
    START_TIME_ TIMESTAMP,
    END_TIME_ TIMESTAMP
);
```

**测试用例**:

| 测试方法 | 场景 | 验证 |
|---------|------|------|
| `scanFromDatabase_skipsWhenNoConnInfo` | config 中无 processEngine.db | 返回 0,不连接 DB |
| `scanFromDatabase_skipsEncryptedPassword` | connInfo.encryptedSkipped=true | 返回 0,不连接 DB |
| `scanFromDatabase_skipsCustomEngine` | engineType=CUSTOM | 返回 0,交给 CustomWorkflowDbAdapter |
| `scanFromDatabase_readsDeployedProcessDefinitions` | H2 插入 1 条 procdef + bytearray(BPMN XML) | graphBuilder.buildBpmnProcessGraph 被调用 1 次 |
| `scanFromDatabase_enrichesRuntimeData` | H2 插入 3 条 hi_taskinst | fact 中 FlowNode.execCount > 0 |
| `scanFromDatabase_buildsRuntimeFlowEdges` | H2 插入相邻 task 迁移 | graphBuilder 被调用构建 RUNTIME_FLOW_TO 边 |
| `scanFromDatabase_failsGracefully` | 故意给错误 jdbcUrl | 不抛异常,返回 0,summary 含 failed |
| `supports_alwaysReturnsFalse` | 任意 asset | 返回 false(不通过 SourceAsset 触发) |
| `capability_returnsCorrectInfo` | - | name=BpmnEngineDbAdapter, frameworks 含 flowable/activiti/camunda |

**BPMN XML 测试 fixture**(内联字符串): 一个最小化的请假流程,含 1 个 UserTask + 1 个 ServiceTask + 1 个 Gateway + 2 个 SequenceFlow。

### 步骤 13: CustomWorkflowDbAdapter(自研引擎,配置驱动)

**文件**: `backend/src/main/java/io/github/legacygraph/extractors/adapter/CustomWorkflowDbAdapter.java`

**职责**: 通过 `workflow.tables.*`/`workflow.columns.*` 配置驱动的表名/字段映射,连接自研流程引擎库,读取流程定义/节点/流转配置/运行时日志。

**关键设计**:

```java
@Slf4j
@Component
public class CustomWorkflowDbAdapter implements ExtractionAdapter {

    private final GraphBuilder graphBuilder;

    public CustomWorkflowDbAdapter(GraphBuilder graphBuilder) {
        this.graphBuilder = graphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        return false; // 由 ProjectScanner 主动调用
    }

    public ExtractionResult scanFromDatabase(ScanContext context) {
        ProcessEngineConnectionInfo connInfo =
                (ProcessEngineConnectionInfo) context.getConfig().get("processEngine.db");
        if (connInfo == null || !connInfo.isConnectable()
                || connInfo.getEngineType() != EngineType.CUSTOM) {
            return ExtractionResult.builder().processedAssets(0).summary("Custom workflow DB scan skipped").build();
        }

        Map<String, String> tableMap = connInfo.getCustomTableMapping();
        Map<String, String> colMap = connInfo.getCustomColumnMapping();
        if (tableMap == null || tableMap.isEmpty()) {
            log.warn("Custom workflow DB scan: no table mapping configured, skipping");
            return ExtractionResult.builder().processedAssets(0).build();
        }

        try (Connection conn = createConnection(connInfo)) {
            // 1. 读流程定义表 → BpmnProcessFact (复用,虽然不是 BPMN 标准,但中间表示兼容)
            List<BpmnProcessFact> facts = readCustomProcessDefinitions(conn, tableMap, colMap);
            // 2. 读流程节点表 → 填充 FlowNodeFact (nodeType 字段值映射: 1→USER_TASK, 2→SERVICE_TASK, 3→GATEWAY)
            enrichCustomNodes(facts, conn, tableMap, colMap);
            // 3. 读流转配置表 → 填充 SequenceFlowFact
            enrichCustomFlows(facts, conn, tableMap, colMap);
            // 4. 读运行时日志表 → 填充运行时属性 + RUNTIME_FLOW_TO 边
            enrichCustomRuntime(facts, conn, tableMap, colMap, context, graphBuilder);
            // 5. 构建图谱
            for (var fact : facts) {
                graphBuilder.buildBpmnProcessGraph(context.getProjectId(), context.getVersionId(), fact);
            }
            return ExtractionResult.builder()
                    .processedAssets(facts.size())
                    .nodeCount(facts.stream().mapToInt(f -> f.getNodes().size() + 1).sum())
                    .summary("Scanned " + facts.size() + " custom workflow processes")
                    .build();
        } catch (Exception e) {
            log.warn("Custom workflow DB scan failed (non-blocking): {}", e.getMessage());
            return ExtractionResult.builder().processedAssets(0)
                    .summary("Custom workflow DB scan failed: " + e.getMessage()).build();
        }
    }

    /** nodeType 值映射: 配置中约定 1=UserTask, 2=ServiceTask, 3=Gateway */
    private BpmnProcessFact.FlowNodeType mapCustomNodeType(String raw) {
        if (raw == null) return BpmnProcessFact.FlowNodeType.USER_TASK;
        return switch (raw.trim()) {
            case "1", "userTask", "USER_TASK", "user" -> BpmnProcessFact.FlowNodeType.USER_TASK;
            case "2", "serviceTask", "SERVICE_TASK", "service" -> BpmnProcessFact.FlowNodeType.SERVICE_TASK;
            case "3", "gateway", "GATEWAY", "decision" -> BpmnProcessFact.FlowNodeType.GATEWAY;
            default -> BpmnProcessFact.FlowNodeType.USER_TASK;
        };
    }

    // createConnection / readCustomProcessDefinitions / enrichCustomNodes / ...
    // 全部通过 tableMap.get("processDefinition") / colMap.get("processKey") 等动态拼接 SQL
    // 不硬编码任何表名/字段名

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("CustomWorkflowDbAdapter")
                .languages(Set.of("sql"))
                .fileTypes(Set.of())
                .frameworks(Set.of("custom-workflow"))
                .aiEnhanced(false)
                .priority(67)
                .build();
    }
}
```

**自研引擎配置约定**(从 application.yml 读取):
```yaml
workflow:
  datasource:
    url: jdbc:mysql://...
    username: ...
    password: ...
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
```

### 步骤 14: ProjectScanner 编排扩展

**文件**: [ProjectScanner.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java)

**改动点 1**: 新增 3 个可选依赖(setter 注入,在 [L161](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java#L161) 附近):

```java
/** 流程引擎配置读取器(可选):从目标项目 application.yml 读取 BPMN 引擎 DB 连接 */
@Autowired(required = false)
private io.github.legacygraph.extractors.bpmn.ProcessEngineConfigExtractor processEngineConfigExtractor;

/** 标准 BPMN 引擎 DB 适配器(可选):扫描 act_ 前缀的流程引擎库 */
@Autowired(required = false)
private io.github.legacygraph.extractors.adapter.BpmnEngineDbAdapter bpmnEngineDbAdapter;

/** 自研流程引擎 DB 适配器(可选):扫描配置驱动的业务流程表 */
@Autowired(required = false)
private io.github.legacygraph.extractors.adapter.CustomWorkflowDbAdapter customWorkflowDbAdapter;
```

**改动点 2**: 在 MAPPER_SQL_LINK 之后([L887](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java#L887) 后)、EXTERNAL_VERIFY 之前([L889](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java#L889) 前)插入流程引擎 DB 扫描阶段:

```java
// === BPMN_DB_SCAN 阶段:流程引擎数据库扫描(文件扫描完成后补充 DB 源 + 运行时数据) ===
if (isCancelled(versionId)) return;
if (shouldScanCode && processEngineConfigExtractor != null
        && (bpmnEngineDbAdapter != null || customWorkflowDbAdapter != null)
        && backendDir != null && !backendDir.isBlank()) {
    ScanTask bpmnDbTask = createTask(projectId, versionId, "BPMN_DB_SCAN", "流程引擎数据库扫描");
    try {
        log.info("Scan still running: projectId={}, versionId={}, phase=BPMN_DB_SCAN, detail=starting",
                projectId, versionId);
        saveCheckpoint(versionId, "BPMN_DB_SCAN", 0, null, 0);

        // 1. 从目标项目配置读取流程引擎连接信息
        ProcessEngineConnectionInfo connInfo = processEngineConfigExtractor.extract(backendDir);
        if (connInfo == null || !connInfo.isConnectable()) {
            completeTask(bpmnDbTask, "no process engine DB config found, skipped",
                    null, "SKIPPED");
            log.info("Scan still running: projectId={}, versionId={}, phase=BPMN_DB_SCAN, detail=skipped (no config)",
                    projectId, versionId);
        } else {
            // 2. 构造 ScanContext(复用 scanAssetsWithAdapters 的 context 或新建)
            ScanContext bpmnContext = ScanContext.builder()
                    .projectId(projectId)
                    .versionId(versionId)
                    .baseDir(baseDir)
                    .backendDir(backendDir)
                    .frontendDir(frontendDir)
                    .config(new java.util.concurrent.ConcurrentHashMap<>())
                    .build();
            bpmnContext.getConfig().put("processEngine.db", connInfo);

            // 3. 按引擎类型分发
            int bpmnNodeCount = 0, bpmnEdgeCount = 0, bpmnProcessCount = 0;
            if (connInfo.getEngineType() == EngineType.CUSTOM) {
                if (customWorkflowDbAdapter != null) {
                    ExtractionResult r = customWorkflowDbAdapter.scanFromDatabase(bpmnContext);
                    bpmnProcessCount = r.getProcessedAssets();
                    bpmnNodeCount = r.getNodeCount();
                    bpmnEdgeCount = r.getEdgeCount();
                }
            } else {
                if (bpmnEngineDbAdapter != null) {
                    ExtractionResult r = bpmnEngineDbAdapter.scanFromDatabase(bpmnContext);
                    bpmnProcessCount = r.getProcessedAssets();
                    bpmnNodeCount = r.getNodeCount();
                    bpmnEdgeCount = r.getEdgeCount();
                }
            }
            completeTask(bpmnDbTask,
                    "scanned " + bpmnProcessCount + " processes, " + bpmnNodeCount + " nodes",
                    null);
            log.info("Scan still running: projectId={}, versionId={}, phase=BPMN_DB_SCAN, detail=completed ({} processes, {} nodes, {} edges)",
                    projectId, versionId, bpmnProcessCount, bpmnNodeCount, bpmnEdgeCount);
        }
        saveCheckpoint(versionId, "BPMN_DB_SCAN", -1, null, 1);
    } catch (Exception e) {
        // 失败隔离:DB 扫描失败不阻塞后续 AI_ORCHESTRATION
        log.warn("BPMN DB scan failed (non-blocking): versionId={}, err={}", versionId, e.getMessage());
        completeTask(bpmnDbTask, "failed: " + e.getMessage(), e.getMessage());
    }
}
```

**为什么不用 isPhaseCompleted 检查**: BPMN_DB_SCAN 是新增阶段,首次扫描无 checkpoint,续扫时即使 checkpoint 存在也只需秒级完成(配置读取+可选 DB 查询),不做断点恢复。

**失败隔离验证**: 故意配置错误 DB 连接 → `bpmnEngineDbAdapter.scanFromDatabase` 内部 catch 异常返回 0,completeTask 记录失败但不抛出,后续 EXTERNAL_VERIFY/AI_ORCHESTRATION 正常执行。

### 步骤 15: 集成验证(编译 + 回归测试)

**验证命令**(按顺序):

1. **编译**:
   ```bash
   cd /Users/huymac/工作/数智/LegacyGraph/backend && mvn compile -q
   ```
   预期:无编译错误,camunda-bpmn-model + snakeyaml 依赖解析正常。

2. **新增单元测试**:
   ```bash
   mvn test -Dtest=ProcessEngineConfigExtractorTest,BpmnEngineDbAdapterTest -q
   ```
   预期:全部通过。

3. **文件源回归**(步骤 1-7 已完成的测试):
   ```bash
   mvn test -Dtest=BpmnModelParserTest,BpmnFileAdapterTest -q
   ```
   预期:全部通过,无回归。

4. **现有适配器回归**:
   ```bash
   mvn test -Dtest=MyBatisXmlAdapterTest,MyBatisAnnotationAdapterTest,JavaCodeAdapterTest -q
   ```
   预期:全部通过,新增 BPMN 适配器注册不影响现有适配器。

5. **ProjectScanner 回归**(如有 ProjectScannerFullFlowTest):
   ```bash
   mvn test -Dtest=ProjectScannerFullFlowTest -q
   ```
   预期:扫描流程正常,新增 BPMN_DB_SCAN 阶段不破坏现有阶段顺序。

6. **适配器注册验证**: 启动后端服务,日志应含:
   ```
   ExtractionAdapterRegistry initialized with N adapters: [..., BpmnFileAdapter, BpmnEngineDbAdapter, CustomWorkflowDbAdapter]
   ```
   N 比改动前增加 2(BpmnFileAdapter 已在步骤 5 注册)。

7. **失败隔离验证**(手动): 配置一个故意错误的 flowable.datasource.url,触发扫描,验证:
   - 日志含 `BPMN DB scan failed (non-blocking)`
   - 扫描不中断,后续 AI_ORCHESTRATION 阶段正常执行
   - ScanVersion 最终状态为 SUCCESS(非 FAILED)

## 关键设计决策

1. **DB 扫描阶段位置**: 插在 MAPPER_SQL_LINK 之后、EXTERNAL_VERIFY 之前。理由:
   - 代码扫描(ADAPTER_SCAN + MEMBER_CALL_RESOLVE + MAPPER_SQL_LINK)必须先完成,这样 BPMN ServiceTask 的 EXECUTES_BY 边才能匹配到已存在的 Service/Method 节点
   - EXTERNAL_VERIFY 之前,因为外部验证可能需要校验 BPMN 流程节点的真实性

2. **ScanContext 复用策略**: 不复用 `scanAssetsWithAdapters` 内部的 context(那是方法局部变量),而是新建一个带 `processEngine.db` 的 context。理由:
   - `scanAssetsWithAdapters` 的 context 是方法局部,外部访问不到
   - BPMN_DB_SCAN 阶段独立,context 字段简单(projectId/versionId/baseDir/backendDir/config)
   - 避免修改 `scanAssetsWithAdapters` 签名增加耦合

3. **可选依赖注入**: 3 个新服务用 `@Autowired(required=false)` setter 注入。理由:
   - 不是所有项目都有流程引擎,Bean 缺失时 ProjectScanner 仍能正常启动
   - 与 ProjectScanner 现有可选依赖风格一致(L-06/L-02/blastRadiusAnalyzer 等)
   - BPMN_DB_SCAN 阶段加 null 检查,缺失时跳过

4. **H2 测试不依赖 Spring Context**: BpmnEngineDbAdapterTest 用纯 JDBC + Mockito。理由:
   - 参考 BpmnFileAdapterTest 的 `@ExtendWith(MockitoExtension.class)` 模式
   - 避免 H2TestConfig 的复杂 Mock 链路
   - 测试更快更稳定

5. **运行时数据时间范围**: 查询 `act_hi_taskinst` 加 `START_TIME_ > {now-90d}` + `LIMIT 10000`。理由:
   - 参考项目 memory 中大文档 OOM 教训,避免全量历史数据
   - 90 天足够反映当前流程热度
   - LIMIT 防止极端情况

6. **jasypt 加密跳过策略**: ENC(...) 值不尝试解密,直接跳过整个 DB 扫描并 log.warn。理由:
   - 项目无 jasypt 依赖,引入解密库增加复杂度
   - 加密密码无法解密则无法连接 DB,扫描必然失败
   - 提示用户手动在 LegacyGraph 扫描配置中补充明文密码

7. **CUSTOM 引擎 nodeType 值映射**: 约定 `1=UserTask, 2=ServiceTask, 3=Gateway`,同时支持字符串(userTask/serviceTask/gateway)和数字。理由:
   - 自研引擎 nodeType 字段值无标准,需约定
   - 多种格式兼容降低配置门槛

8. **BpmnEngineDbAdapter 与 CustomWorkflowDbAdapter 不通过 SourceAsset 触发**: `supports()` 永远返回 false。理由:
   - DB 源没有对应的 SourceAsset 文件
   - 由 ProjectScanner 主动调用 `scanFromDatabase(context)`,绕过 Adapter 执行链
   - 但仍注册为 `@Component`,可通过 `extractionAdapterRegistry.getAllAdapters()` 列出(capability 可见)

## 验证步骤总结

| # | 验证项 | 命令/方法 | 预期 |
|---|--------|----------|------|
| 1 | 编译 | `mvn compile -q` | 无错误 |
| 2 | ProcessEngineConfigExtractorTest | `mvn test -Dtest=ProcessEngineConfigExtractorTest` | 11 用例通过 |
| 3 | BpmnEngineDbAdapterTest | `mvn test -Dtest=BpmnEngineDbAdapterTest` | 9 用例通过 |
| 4 | 文件源回归 | `mvn test -Dtest=BpmnModelParserTest,BpmnFileAdapterTest` | 全部通过 |
| 5 | 现有适配器回归 | `mvn test -Dtest=MyBatisXmlAdapterTest` | 全部通过 |
| 6 | 适配器注册 | 启动服务看日志 | N 增加 2,含 BpmnEngineDbAdapter/CustomWorkflowDbAdapter |
| 7 | 失败隔离 | 配置错误 DB 连接触发扫描 | 扫描不中断,version=SUCCESS |

## 假设与风险

1. **假设: snakeyaml 可用**: spring-boot-starter 传递依赖 snakeyaml,无需显式引入。若实测发现不可用,需在 pom.xml 加 `org.yaml:snakeyaml` 依赖。
2. **假设: act_ 表名大小写**: Flowable/Activiti/Camunda 的表名固定为 `act_re_procdef` 等小写,列名带下划线大写(`ID_`/`KEY_`)。H2 默认大小写不敏感,MySQL/PostgreSQL 需确认。用 `ResultSetMetaData` 做大小写不敏感查找兜底。
3. **风险: 自研引擎多样性**: CUSTOM 引擎的表结构千差万别,配置驱动的映射无法覆盖所有情况。本期只支持「单流程定义表 + 单节点表 + 单流转表 + 单日志表」的最简模型,复杂场景(如多表 join、嵌套流程)留待后续。
4. **风险: 运行时数据规模**: `act_hi_taskinst` 在生产环境可能数百万行。LIMIT 10000 + 90 天过滤是权宜之计,超大库可能仍慢。后续可加分页查询或异步导出。
5. **不做: ProcessDefinition 与 BusinessProcess 节点关联**: 本期不建立 BPMN 流程定义节点与 BusinessProcessAdapter 推断节点的对应关系,两者独立进图谱(原计划「后续阶段」第 1 项)。
6. **不做: 流程变量分析**: `act_hi_varinst` 不扫描,流程变量与 BusinessObject 的关联留待后续。
