# BPMN 流程引擎扫描支持(文件 + 数据库双源)

## 摘要

为 LegacyGraph 补充流程引擎项目的扫描能力,解决「带流程引擎的项目业务逻辑主要在流程数据中,纯代码扫描无法构建完善业务图谱」的盲区。

**关键认知**: 流程引擎项目的 BPMN 数据有两个来源——
1. **源码仓库**: `.bpmn`/`.bpmn20.xml` 文件(部分项目)
2. **流程引擎数据库**: `act_re_procdef`+`act_ge_bytearray` 存储已部署的 BPMN XML,`act_ru_task`/`act_hi_taskinst` 存储运行时数据(几乎所有项目)

很多老项目的流程定义通过设计器直接部署到数据库,源码里根本没有 `.bpmn` 文件。因此必须同时支持文件源和数据库源,并包含运行时分析。

本方案新增 3 个适配器:
- `BpmnFileAdapter` — 解析源码中的 `.bpmn` 文件
- `BpmnEngineDbAdapter` — 连接标准 BPMN 引擎库(Flowable/Activiti/Camunda,act_ 前缀),读取部署的流程定义 + 运行时数据
- `CustomWorkflowDbAdapter` — 连接自研流程引擎库(业务表),通过配置驱动表名/字段映射

数据库连接信息从**目标项目配置文件**(`application.yml`/`properties`)自动读取,无需用户手动配置。

## 现状分析(Phase 1 探索结论)

### 现有流程扫描能力(不足)
- [BusinessProcessAdapter.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/extractors/adapter/BusinessProcessAdapter.java#L25-L28) `supports()` 只接受 `.java`,从 `@Service` 方法推断流程
- [BusinessProcessExtractor.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/extractors/BusinessProcessExtractor.java#L17-L25) 对流程引擎项目是在「猜」,真正的 BPMN 定义和运行时数据完全没解析

### 资产发现阶段缺口
- [AssetDiscoveryService.classifyAssetKind](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/AssetDiscoveryService.java#L302-L309) `switch(ext)` 对 `bpmn` 无 case
- [ProjectScanner](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java#L2425-L2439) `isCodeFile()` 白名单无 `.bpmn`

### 架构扩展点(已具备)
- [ExtractionAdapter](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/extractors/adapter/ExtractionAdapter.java) + [ExtractionAdapterRegistry](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/extractors/adapter/ExtractionAdapterRegistry.java#L24-L31) Spring 自动注入
- [NodeType](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/common/NodeType.java) / [EdgeType](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/common/EdgeType.java) 枚举可追加
- [GraphBuilder](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/builder/GraphBuilder.java#L3848-L3887) `buildNode`/`buildEdge`/`findExistingNode`/`mergeNodesBatch`/`mergeEdgesBatch` 可复用
- [ScanContext](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/extractors/adapter/ScanContext.java) 有 `config` Map,可携带 DB 连接信息
- pom.xml 无 flowable/camunda 依赖

### 配置读取参考
- [ProjectConventionIngestService](file:////Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/service/scan/ProjectConventionIngestService.java#L253) 已有读取目标项目 `pom.xml` 的先例,可参考其读取 `application.yml` 的方式

## 决策(用户已确认)

1. **引擎覆盖**: 标准 BPMN 引擎(Flowable/Activiti/Camunda,act_ 前缀)+ 自研流程引擎(业务表)两者都要支持
2. **DB 连接来源**: 从目标项目配置文件(`application.yml`/`properties`)自动读取,不要求用户手动配置
3. **解析方式**: 引入 `org.camunda.bpm.model:camunda-bpmn-model:7.24.0`(7.24.0 是 Maven Central 最后社区版,BPMN 2.0 标准稳定)
4. **节点建模**: 细分为 `ProcessDefinition` + `UserTask` + `ServiceTask` + `Gateway` 多类节点

## 整体架构

```
被扫描项目
├── 源码 .bpmn 文件 ──────────────► BpmnFileAdapter ──► BpmnModelParser ──┐
├── application.yml (datasource) ─► ProcessEngineConfigExtractor           │
│                                                         │                │
│   ┌─────────────────────────────────────────────────────┘                │
│   ▼                                                                      │
│   标准 BPMN 引擎库 (act_ 前缀)                                           │
│   ├── act_re_procdef + act_ge_bytearray (BPMN XML) ──► BpmnModelParser ──┤
│   ├── act_ru_task (活动任务) ─────────────────────────┐                  │
│   └── act_hi_taskinst + act_hi_procinst (历史) ───────┤                  │
│                                                        ▼                  │
│                                               ProcessRuntimeAnalyzer   │
│   自研流程引擎库 (业务表,配置驱动)                                       │
│   ├── 流程定义表 ──► CustomWorkflowDbAdapter (映射为 ProcessDefinition)  │
│   ├── 流程节点表 ──► (映射为 UserTask/ServiceTask/Gateway)               │
│   └── 流转日志表 ──► (映射为 FLOW_TO 边 + 运行时频次)                     │
│                                                                          ▼
│                                                               GraphBuilder
│                                                            .buildBpmnProcessGraph
└────────────────────────────────────────────────────────────────────► Neo4j
```

## 改动清单

### 1. 依赖引入

**文件**: `backend/pom.xml`

```xml
<dependency>
    <groupId>org.camunda.bpm.model</groupId>
    <artifactId>camunda-bpmn-model</artifactId>
    <version>7.24.0</version>
</dependency>
```

### 2. 节点/边类型扩展

**文件**: [NodeType.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/common/NodeType.java)

在 `TransactionScope` 后追加:
```java
// ========== BPMN 流程引擎 ==========
ProcessDefinition("流程定义"),
UserTask("用户任务"),
ServiceTask("服务任务"),
Gateway("网关"),
```
注: 运行时统计(执行频次、平均时长、驳回率)作为 `FlowNode` 节点的属性存储,不新增节点类型,避免节点膨胀。

**文件**: [EdgeType.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/common/EdgeType.java)

在 `BOUND_BY` 后追加:
```java
// ========== BPMN 流程引擎 ==========
FLOW_TO("流转到"),            // 定义层: FlowNode --FLOW_TO--> FlowNode (SequenceFlow, 带 condition)
RUNTIME_FLOW_TO("运行时流转"),  // 运行时: FlowNode --RUNTIME_FLOW_TO--> FlowNode (实际发生, 带 flowCount 频次)
HAS_FLOW_NODE("包含流程节点"),   // ProcessDefinition --HAS_FLOW_NODE--> UserTask/ServiceTask/Gateway
EXECUTES_BY("由...执行"),       // ServiceTask --EXECUTES_BY--> Service/Method
LISTENED_BY("被...监听"),       // FlowNode --LISTENED_BY--> Service/Method
DEPLOYED_TO("部署到"),          // ProcessDefinition --DEPLOYED_TO--> ExternalSystem(流程引擎)
```

### 3. 资产发现补全

**文件**: [AssetDiscoveryService.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/AssetDiscoveryService.java#L302-L309)

`classifyAssetKind` switch 增加:
```java
case "bpmn" -> "BPMN";
```
`xml` case 中增加: `relativePath.endsWith(".bpmn20.xml")` → `"BPMN"`,优先于 Mapper 判断。

**文件**: [ProjectScanner.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java#L2425-L2439)

`isCodeFile()` 增加 `name.endsWith(".bpmn")` 和 `name.endsWith(".bpmn20.xml")`。

### 4. BpmnModelParser(新建,共享解析器)

**文件**: `backend/src/main/java/io/github/legacygraph/extractors/bpmn/BpmnModelParser.java`

文件源和数据库源共用的 BPMN 解析器。输入是 BPMN XML(以 `InputStream` 或 `File` 形式),输出统一的 `BpmnProcessFact`。

```java
@Component
public class BpmnModelParser {
    
    /** 从文件解析 */
    public BpmnProcessFact parseFromFile(File file) {
        try {
            BpmnModelInstance model = Bpmn.readModelFromFile(file);
            return parseModel(model, file.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to parse BPMN file {}: {}", file.getName(), e.getMessage());
            return null;
        }
    }
    
    /** 从输入流解析(用于数据库读取的 BPMN XML) */
    public BpmnProcessFact parseFromStream(InputStream stream, String sourcePath) {
        try {
            BpmnModelInstance model = Bpmn.readModelFromStream(stream);
            return parseModel(model, sourcePath);
        } catch (Exception e) {
            log.warn("Failed to parse BPMN from stream {}: {}", sourcePath, e.getMessage());
            return null;
        }
    }
    
    private BpmnProcessFact parseModel(BpmnModelInstance model, String sourcePath) {
        Collection<Process> processes = model.getModelElementsByType(Process.class);
        // 遍历 Process,提取:
        // - UserTask: id/name/assignee/candidateGroups/formKey + TaskListener class
        // - ServiceTask: id/name + camunda:class/expression/delegateExpression + ExecutionListener
        // - Gateway: id/name + subType(Exclusive/Parallel/Inclusive)
        // - SequenceFlow: source/target + conditionExpression
        // 命名空间兼容: camunda: 原生; flowable:/activiti: 通过 getAttributeNs(prefix, name) 别名读取
        // 表达式 ${service.method(args)} → 正则提取 beanName.methodName → exprRefs
        // 类引用 → classRefs(含全限定类名/beanName/sourceNodeId/sourceType)
    }
}
```

**输出模型** `BpmnProcessFact`(放 `extractors/bpmn/` 包):
```java
@Data @Builder
public class BpmnProcessFact {
    private String processKey;
    private String processName;
    private String sourcePath;           // 文件路径 或 "db:{procDefId}"
    private String deploymentId;         // DB 源才有
    private int version;                 // DB 源才有
    private List<FlowNodeFact> nodes;
    private List<SequenceFlowFact> flows;
    private List<ClassRefFact> classRefs;
    private List<ExprRefFact> exprRefs;
}
```

### 5. ProcessEngineConfigExtractor(新建,配置读取)

**文件**: `backend/src/main/java/io/github/legacygraph/extractors/bpmn/ProcessEngineConfigExtractor.java`

从目标项目配置文件自动读取流程引擎 DB 连接信息。

```java
@Component
public class ProcessEngineConfigExtractor {
    
    /**
     * 扫描目标项目,提取流程引擎数据库连接信息。
     * 扫描位置: backendDir 下的 application*.yml / application*.properties
     */
    public ProcessEngineConnectionInfo extract(String backendDir) {
        // 1. 查找配置文件
        List<Path> configFiles = findConfigFiles(backendDir);
        
        // 2. 按优先级解析 datasource:
        //    - flowable.datasource.* (Flowable 独立数据源)
        //    - activiti.datasource.* (Activiti 独立数据源)
        //    - spring.datasource.* (复用主数据源,最常见)
        // 3. 识别引擎类型:
        //    - 有 flowable.* 配置 → FLOWABLE
        //    - 有 activiti.* 配置 → ACTIVITI  
        //    - 有 camunda.* 配置 → CAMUNDA
        //    - 都没有但 DB 里有 act_ 表 → 推断为 FLOWABLE/ACTIVITI
        // 4. 自研流程引擎: 扫描 workflow.* / process.* / flow.* 配置项
        // 5. 加密配置(jasypt)跳过,log.warn,不阻塞
    }
}

@Data @Builder
public class ProcessEngineConnectionInfo {
    private EngineType engineType;       // FLOWABLE / ACTIVITI / CAMUNDA / CUSTOM
    private String jdbcUrl;
    private String username;
    private String password;
    private String driverClassName;
    private String tablePrefix;          // 通常 "act_"
    // CUSTOM 引擎专用:
    private Map<String, String> customTableMapping;  // 表名映射
}
```

**配置读取策略**:
- 用 snakeyaml 解析 `.yml`,Properties 解析 `.properties`
- 支持多 profile: `application.yml`(基础) + `application-prod.yml`(覆盖)
- jasypt 加密的值(ENC(...) 形式)跳过并 log.warn
- 连接信息写入 `ScanContext.config`,key 为 `processEngine.db`

### 6. BpmnFileAdapter(新建,文件源)

**文件**: `backend/src/main/java/io/github/legacygraph/extractors/adapter/BpmnFileAdapter.java`

```java
@Slf4j @Component
public class BpmnFileAdapter implements ExtractionAdapter {
    private final BpmnModelParser parser;
    private final GraphBuilder graphBuilder;
    
    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        String path = asset.getRelativePath();
        return path != null && (path.endsWith(".bpmn") || path.endsWith(".bpmn20.xml"));
    }
    
    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        BpmnProcessFact fact = parser.parseFromFile(asset.getFile().toFile());
        if (fact == null || fact.getNodes().isEmpty()) {
            return ExtractionResult.builder().processedAssets(0).build();
        }
        graphBuilder.buildBpmnProcessGraph(context.getProjectId(), context.getVersionId(), fact);
        return ExtractionResult.builder()
            .processedAssets(1)
            .nodeCount(fact.getNodes().size() + 1)
            .summary("Scanned BPMN file: " + fact.getProcessKey())
            .build();
    }
    
    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
            .name("BpmnFileAdapter")
            .languages(Set.of("xml"))
            .fileTypes(Set.of("bpmn", "bpmn20.xml"))
            .frameworks(Set.of("bpmn", "flowable", "camunda", "activiti"))
            .aiEnhanced(false)
            .priority(65)
            .build();
    }
}
```

### 7. BpmnEngineDbAdapter(新建,标准 BPMN DB 源)

**文件**: `backend/src/main/java/io/github/legacygraph/extractors/adapter/BpmnEngineDbAdapter.java`

连接标准 BPMN 引擎库(act_ 前缀),读取部署的流程定义 + 运行时数据。

```java
@Slf4j @Component
public class BpmnEngineDbAdapter implements ExtractionAdapter {
    private final ProcessEngineConfigExtractor configExtractor;
    private final BpmnModelParser parser;
    private final ProcessRuntimeAnalyzer runtimeAnalyzer;
    private final GraphBuilder graphBuilder;
    
    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        // 不是基于 SourceAsset 触发,而是基于 ScanContext.config 中的流程引擎连接信息
        // 此 Adapter 由 ProjectScanner 特殊编排调用(见 §10)
        return false;
    }
    
    /**
     * 由 ProjectScanner 在文件扫描完成后调用,独立于 SourceAsset 流程。
     */
    public void scanFromDatabase(ScanContext context) {
        ProcessEngineConnectionInfo connInfo = 
            (ProcessEngineConnectionInfo) context.getConfig().get("processEngine.db");
        if (connInfo == null || connInfo.getEngineType() == EngineType.CUSTOM) {
            return;  // 无连接信息或自研引擎,跳过
        }
        
        try (Connection conn = createConnection(connInfo)) {
            // 1. 读取已部署的流程定义
            List<BpmnProcessFact> facts = readDeployedProcessDefinitions(conn, connInfo.getTablePrefix());
            
            // 2. 读取运行时数据,增强到 facts
            runtimeAnalyzer.enrichWithRuntimeData(facts, conn, connInfo.getTablePrefix());
            
            // 3. 构建图谱
            for (BpmnProcessFact fact : facts) {
                graphBuilder.buildBpmnProcessGraph(
                    context.getProjectId(), context.getVersionId(), fact);
            }
            
            // 4. 构建运行时流转边(RUNTIME_FLOW_TO)
            runtimeAnalyzer.buildRuntimeFlowEdges(
                context.getProjectId(), context.getVersionId(), conn, connInfo.getTablePrefix(),
                graphBuilder);
            
        } catch (Exception e) {
            log.warn("BPMN DB scan failed (non-blocking): {}", e.getMessage());
            // 失败不阻塞,遵循 external-verification 的失败隔离原则
        }
    }
    
    private List<BpmnProcessFact> readDeployedProcessDefinitions(Connection conn, String prefix) 
            throws SQLException {
        // SQL:
        // SELECT p.ID_, p.KEY_, p.NAME_, p.VERSION_, p.DEPLOYMENT_ID_, b.BYTES_
        // FROM ${prefix}RE_PROCDEF p
        // JOIN ${prefix}GE_BYTEARRAY b ON p.DEPLOYMENT_ID_ = b.DEPLOYMENT_ID_
        // WHERE b.NAME_ LIKE '%.bpmn'
        // 
        // 对每行 BYTES_(BPMN XML 二进制):
        // parser.parseFromStream(new ByteArrayInputStream(bytes), "db:" + procDefId)
    }
    
    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        // 不通过 SourceAsset 触发
        return ExtractionResult.builder().processedAssets(0).build();
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

**关键 SQL**(act_ 前缀,参数化 tablePrefix):

| 用途 | SQL |
|------|-----|
| 流程定义 | `SELECT ID_, KEY_, NAME_, VERSION_, DEPLOYMENT_ID_ FROM ${p}RE_PROCDEF` |
| BPMN XML | `SELECT BYTES_ FROM ${p}GE_BYTEARRAY WHERE DEPLOYMENT_ID_=? AND NAME_ LIKE '%.bpmn'` |
| 节点频次 | `SELECT TASK_DEF_KEY_, COUNT(*) FROM ${p}HI_TASKINST GROUP BY TASK_DEF_KEY_` |
| 节点平均时长 | `SELECT TASK_DEF_KEY_, AVG(TIMESTAMPDIFF(SECOND, START_TIME_, END_TIME_)) FROM ${p}HI_TASKINST WHERE END_TIME_ IS NOT NULL GROUP BY TASK_DEF_KEY_` |
| 路径频次 | 按 PROC_INST_ID_ 分组,按 START_TIME_ 排序,提取相邻 TASK_DEF_KEY_ 迁移 |
| 驳回检测 | 同一 PROC_INST_ID_ 中 TASK_DEF_KEY_ 重复出现(回退) |

### 8. ProcessRuntimeAnalyzer(新建,运行时分析)

**文件**: `backend/src/main/java/io/github/legacygraph/extractors/bpmn/ProcessRuntimeAnalyzer.java`

```java
@Component
public class ProcessRuntimeAnalyzer {
    
    /**
     * 用运行时数据增强 BpmnProcessFact:
     * - 每个 FlowNode 增加 execCount(执行次数)、avgDurationMs(平均时长)、rejectRate(驳回率)
     * - 每个 SequenceFlow 增加 flowCount(实际流转次数)
     */
    public void enrichWithRuntimeData(List<BpmnProcessFact> facts, Connection conn, String prefix) {
        // 1. 查节点频次 → 写入 FlowNodeFact.execCount
        // 2. 查节点时长 → 写入 FlowNodeFact.avgDurationMs
        // 3. 查路径频次 → 写入 SequenceFlowFact.flowCount
        // 4. 查驳回 → 写入 FlowNodeFact.rejectRate
    }
    
    /**
     * 构建 RUNTIME_FLOW_TO 边:
     * 历史数据中实际发生的流转(区别于定义层的 FLOW_TO)。
     * 用于发现:定义里没有但实际发生的路径、高频/低频路径、瓶颈节点。
     */
    public void buildRuntimeFlowEdges(String projectId, String versionId, 
            Connection conn, String prefix, GraphBuilder graphBuilder) {
        // 查 act_hi_taskinst,按 proc_inst_id 分组,提取相邻节点迁移
        // 对每条迁移,如果对应的 FLOW_TO 边已存在,增强 flowCount 属性
        // 如果不存在(动态流转/驳回),新建 RUNTIME_FLOW_TO 边,confidence=0.85, status=PENDING_CONFIRM
    }
}
```

### 9. CustomWorkflowDbAdapter(新建,自研流程引擎)

**文件**: `backend/src/main/java/io/github/legacygraph/extractors/adapter/CustomWorkflowDbAdapter.java`

通过配置驱动的表名/字段映射,支持自研流程引擎(状态机表/流转配置表)。

```java
@Slf4j @Component
public class CustomWorkflowDbAdapter implements ExtractionAdapter {
    private final ProcessEngineConfigExtractor configExtractor;
    private final GraphBuilder graphBuilder;
    
    /**
     * 由 ProjectScanner 在文件扫描完成后调用。
     */
    public void scanFromDatabase(ScanContext context) {
        ProcessEngineConnectionInfo connInfo = 
            (ProcessEngineConnectionInfo) context.getConfig().get("processEngine.db");
        if (connInfo == null || connInfo.getEngineType() != EngineType.CUSTOM) {
            return;
        }
        
        Map<String, String> mapping = connInfo.getCustomTableMapping();
        // mapping 示例:
        //   "processDefinition" → "t_flow_definition"
        //   "flowNode"          → "t_flow_node"
        //   "sequenceFlow"      → "t_flow_transition"
        //   "runtimeLog"        → "t_flow_log"
        // 字段映射同理(processKey/name/sourceNode/targetNode/condition...)
        
        try (Connection conn = createConnection(connInfo)) {
            // 1. 读流程定义表 → ProcessDefinition 节点
            // 2. 读流程节点表 → UserTask/ServiceTask/Gateway 节点(按 nodeType 字段映射)
            // 3. 读流转配置表 → FLOW_TO 边
            // 4. 读运行时日志表 → RUNTIME_FLOW_TO 边 + 节点频次属性
            // 全部通过 mapping 配置驱动 SQL,不硬编码表名/字段
        } catch (Exception e) {
            log.warn("Custom workflow DB scan failed (non-blocking): {}", e.getMessage());
        }
    }
    
    // supports() / extract() / capability() 同 BpmnEngineDbAdapter 模式
}
```

**自研引擎配置约定**(从目标项目 `application.yml` 读取或用户在 LegacyGraph 扫描配置中补充):
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
    nodeType: node_type   # 值映射: 1=UserTask, 2=ServiceTask, 3=Gateway
    sourceNode: from_node
    targetNode: to_node
    condition: condition_expr
```

### 10. ProjectScanner 编排扩展

**文件**: [ProjectScanner.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java)

在文件扫描阶段完成后,新增「流程引擎 DB 扫描」阶段:

```java
// 在现有 ADAPTER_SCAN 阶段末尾、AI_ORCHESTRATION 阶段之前插入:
private void executeProcessEngineDbScan(ScanContext context) {
    // 1. 从目标项目配置读取流程引擎连接信息
    ProcessEngineConnectionInfo connInfo = configExtractor.extract(context.getBackendDir());
    if (connInfo != null) {
        context.getConfig().put("processEngine.db", connInfo);
        
        // 2. 按引擎类型分发
        switch (connInfo.getEngineType()) {
            case FLOWABLE, ACTIVITI, CAMUNDA -> 
                bpmnEngineDbAdapter.scanFromDatabase(context);
            case CUSTOM -> 
                customWorkflowDbAdapter.scanFromDatabase(context);
        }
    }
}
```

**失败隔离**: DB 扫描失败不阻塞后续 AI_ORCHESTRATION 阶段(遵循项目 memory 中「External verification failures must not block subsequent scanning steps」原则)。

### 11. GraphBuilder.buildBpmnProcessGraph(新建)

**文件**: [GraphBuilder.java](file:///Users/huymac/工作/数智/LegacyGraph/backend/src/main/java/io/github/legacygraph/builder/GraphBuilder.java#L3843) (在 `buildBusinessProcessGraph` 后追加)

```java
/**
 * 构建 BPMN 流程图谱(文件源 + DB 源统一入口):
 * - ProcessDefinition 节点(含 version、deploymentId 属性)
 * - UserTask/ServiceTask/Gateway 节点(含 execCount/avgDurationMs/rejectRate 运行时属性)
 * - HAS_FLOW_NODE 边 (ProcessDefinition → FlowNode)
 * - FLOW_TO 边 (定义层 SequenceFlow,带 condition + flowCount)
 * - EXECUTES_BY 边 (ServiceTask → Service/Method,通过 class/expression 引用)
 * - LISTENED_BY 边 (FlowNode → Service/Method,通过 Listener class)
 */
public void buildBpmnProcessGraph(String projectId, String versionId, BpmnProcessFact fact) {
    // 1. ProcessDefinition 节点, nodeKey = "bpmn.process:{processKey.lower}", 
    //    properties 含 version/deploymentId/sourceType(FILE/DB)
    // 2. FlowNode 节点, nodeKey = "bpmn.{type}:{processKey}.{bpmnId}",
    //    properties 含 execCount/avgDurationMs/rejectRate(运行时增强)
    // 3. HAS_FLOW_NODE 边
    // 4. FLOW_TO 边, edgeKey = "bpmn.flow:{processKey}.{src}->{tgt}",
    //    properties 含 condition/flowCount
    // 5. EXECUTES_BY 边: ServiceTask → Service 节点
    //    匹配策略: camunda:class 短类名匹配 / delegateExpression beanName 匹配
    // 6. LISTENED_BY 边: Listener class → Service/Method 节点
    // 7. 表达式 ${service.method()} → Method 节点(按 beanName+methodName 匹配)
    // 匹配失败的引用不建边,log.warn
}
```

### 12. 单元测试

**文件**: `backend/src/test/java/io/github/legacygraph/extractors/bpmn/BpmnModelParserTest.java`
- Fixture: `processes/leave.bpmn`(UserTask + ServiceTask + Gateway + SequenceFlow)
- 验证: 节点数、表达式引用 `leaveService.approve`、类引用解析

**文件**: `backend/src/test/java/io/github/legacygraph/extractors/adapter/BpmnFileAdapterTest.java`
- `supports()` 对 `.bpmn`/`.bpmn20.xml`/`.java`/`pom.xml` 判断
- `extract()` 返回正确 nodeCount
- `capability().frameworks` 含 `bpmn`

**文件**: `backend/src/test/java/io/github/legacygraph/extractors/bpmn/ProcessEngineConfigExtractorTest.java`
- Fixture: `application.yml` 含 `spring.datasource` + `flowable.*` 配置
- 验证: 正确提取 jdbcUrl/user/pwd/engineType
- 加密值(ENC(...))跳过

**文件**: `backend/src/test/java/io/github/legacygraph/extractors/bpmn/BpmnEngineDbAdapterTest.java`
- 使用 H2 内存库,初始化 act_re_procdef/act_ge_bytearray/act_hi_taskinst 表
- 插入一条 BPMN XML + 历史任务数据
- 验证: `scanFromDatabase()` 生成 ProcessDefinition 节点 + 运行时属性

## 假设与决策

1. **三数据源优先级**: 文件源先扫(若有),DB 源后扫(补充或覆盖)。同一 processKey 的流程定义,DB 源的 version 更高时覆盖文件源节点(nodeKey 相同,mergeNodesBatch 自动去重)。
2. **DB 连接失败不阻塞**: 遵循项目现有「External verification failures must not block subsequent scanning steps」原则,DB 扫描失败仅 log.warn,不影响 AI_ORCHESTRATION 阶段。
3. **加密配置处理**: jasypt 加密的 datasource 密码(ENC(...) 形式)无法解密,跳过该数据源扫描并 log.warn 提示用户手动配置。
4. **运行时数据规模**: `act_hi_taskinst` 可能很大,查询加 `LIMIT 10000` + 按时间范围过滤(最近 90 天),避免 OOM(参考项目 memory 中大文档 OOM 教训)。
5. **自研引擎配置缺失**: 若目标项目无 `workflow.tables` 配置,CustomWorkflowDbAdapter 跳过,不报错。用户可在 LegacyGraph 扫描配置中手动补充表映射。
6. **表达式解析深度**: `${service.method(args)}` 仅提取 beanName + methodName 做节点关联,不解析参数和 SpEL 复杂表达式。网关条件 `${days>3}` 作为 FLOW_TO 边属性存储。
7. **与现有 BusinessProcessAdapter 共存**: 两者不互斥。BusinessProcessAdapter 继续从 Java 代码推断(适用非流程引擎项目);BpmnFileAdapter/BpmnEngineDbAdapter 处理真实流程定义。两者结果都进图谱。
8. **camunda-bpmn-model 版本**: 7.24.0 是 Maven Central 最后社区版(2025-10)。BPMN 2.0 标准稳定,作为只读解析库无维护风险。原生支持 `camunda:` 前缀;`flowable:`/`activiti:` 前缀通过 `getAttributeNs(prefix, name)` 别名读取。

## 验证步骤

1. **编译**: `cd backend && mvn compile` 通过,无依赖冲突
2. **单元测试**: 
   - `mvn test -Dtest=BpmnModelParserTest,BpmnFileAdapterTest`
   - `mvn test -Dtest=ProcessEngineConfigExtractorTest`
   - `mvn test -Dtest=BpmnEngineDbAdapterTest`(H2 内存库)
3. **回归**: `mvn test -Dtest=MyBatisXmlAdapterTest,ProjectScannerFullFlowTest` 现有测试不受影响
4. **集成验证**(手动,需准备含流程引擎的目标项目):
   - 文件源: 目标项目 `src/main/resources/processes/` 有 `.bpmn` 文件 → 扫描后图谱有 ProcessDefinition/UserTask/ServiceTask/Gateway 节点
   - DB 源: 目标项目 `application.yml` 配置了 flowable datasource → 扫描日志含 `BPMN DB scan` → 图谱有部署的流程定义节点(即使源码无 .bpmn 文件)
   - 运行时: 图谱中 FlowNode 节点有 `execCount`/`avgDurationMs` 属性,存在 `RUNTIME_FLOW_TO` 边
5. **适配器注册**: 启动日志 `ExtractionAdapterRegistry initialized with N adapters` 中 N 增加 3,含 `BpmnFileAdapter`/`BpmnEngineDbAdapter`/`CustomWorkflowDbAdapter`
6. **失败隔离验证**: 故意配置错误的 DB 连接 → 扫描不中断,日志有 warn,后续 AI_ORCHESTRATION 阶段正常执行

## 实施顺序

1. pom.xml 加 camunda-bpmn-model 依赖 → 编译验证
2. NodeType / EdgeType 枚举扩展
3. AssetDiscoveryService / ProjectScanner 资产发现补全(.bpmn 扩展名)
4. BpmnModelParser(共享解析器)+ BpmnProcessFact 模型
5. BpmnFileAdapter(文件源,最简单,先跑通)
6. GraphBuilder.buildBpmnProcessGraph
7. BpmnModelParserTest + BpmnFileAdapterTest(文件源闭环验证)
8. ProcessEngineConfigExtractor(配置读取)
9. ProcessEngineConfigExtractorTest
10. BpmnEngineDbAdapter(标准 BPMN DB 源)
11. ProcessRuntimeAnalyzer(运行时分析)
12. BpmnEngineDbAdapterTest(H2 内存库)
13. CustomWorkflowDbAdapter(自研引擎,配置驱动)
14. ProjectScanner 编排扩展(插入流程引擎 DB 扫描阶段)
15. 集成验证

## 后续阶段(本期不做)

- ProcessDefinition 节点与 BusinessProcess 节点的自动关联(对流程引擎项目,两者结果都进图谱,需建立对应关系)
- 流程变量(act_hi_varinst)深度分析,关联到 BusinessObject 节点
- AI 增强:对匹配失败的 ServiceTask/Listener 类引用,用 LLM 推断关联
- 流程版本演进分析:对比同一 processKey 不同 version 的 BPMN 差异
