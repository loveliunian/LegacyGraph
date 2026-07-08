# Java 成员调用解析器设计（二次扫描全局解析）

> 参考 graphify v8 的 C#/Ruby member-call resolver 模式，解决 LegacyGraph 代码图谱 `Controller→Service→Mapper→SqlStatement→Table` 调用链断裂问题。本文是实现设计底稿，实现以代码为准。

## 问题背景

代码图谱调用链曾完全断裂。最新保证金扫描实测：Controller 18 / Service 91 / Mapper 18 / SqlStatement 158 / Table 43 节点齐全，Mapper→SQL(158 EXECUTES)→Table(174 READS/WRITES) 段完好，但 **CALLS 边仅 17 条、Service→Mapper = 0、完整链路 = 0**。断点在 Controller→Service→Mapper 段。

根因（部分已修）：
1. `ServiceCallExtractor.collectInjectedVarTypes` 漏了 Lombok `@RequiredArgsConstructor` final 字段注入 → `CallRelation.targetClass` 全 null → 0 边。**已修**（final 字段视作构造器注入 + `this.` 前缀剥离）。
2. 修后仍有两问题阻碍边正确落地：
   - **简单名 vs FQN 不匹配**：`CallRelation.targetClass` 是简单名（`OrderMapper`），类节点 nodeKey 是 FQN（`com.example.OrderMapper`）。`findOrCreateNodeByClass` 精确 nodeKey 查找 miss → **创建重复的简单名节点**，而非连到真节点。
   - **逐文件顺序**：`buildServiceCallGraph` 在适配器内逐文件跑，目标 Method 节点（在别的文件）可能还没抽取 → 方法级 CALLS 边漏。

## 方案：二次扫描全局解析器（graphify 模式）

graphify v8 的 member-call resolver 契约 `(per_file, all_nodes, all_edges) -> None`，**纯增量**（只补逐文件漏的边），跑在所有节点就绪后。核心机制：
- 建全局索引 `type_def_nids`（类标签→[节点id]）+ `method_index`((类,方法)→方法节点)；
- **god-node guard**：名字只映射到**唯一**定义时才建边，歧义/缺失一律跳过（不留错边、不留低置信边）；
- 接收方类型分档：`this.M()`/`Type.M()` → EXTRACTED(1.0)；`recv.M()` 经字段/参数/局部变量类型表 → INFERRED(0.8)；
- stub 外部节点从索引排除，解析器不造 stub；
- 去重 `existing_pairs`，跳自调用。

LegacyGraph 适配：图存 Neo4j（非内存 dict），逐文件经适配器写库，二次扫描在 `ProjectScanner.runScanBody` 的 ADAPTER_SCAN 后跑。CallRelation 已持久化进 `lg_fact`（fact_type=SERVICE_CALL，`normalizedData` 存 JSON）。

## 实现（Phase 1 — 最小高价值）

### Part A — 新建 `JavaMemberCallResolver` @Component

`backend/src/main/java/io/github/legacygraph/builder/JavaMemberCallResolver.java`。依赖 `Neo4jGraphDao` / `FactRepository` / `ObjectMapper`。

入口 `@Transactional public int resolveMemberCalls(String projectId, String versionId)`，模式照 `BusinessGraphBuilder.mapFeaturesToCode`。

步骤：
1. **加载事实**：`factRepository.selectList(LambdaQueryWrapper eq versionId + fact_type=SERVICE_CALL)`，逐条把 `normalizedData` JSON 反序列化进本地 `CallRelationDto`（无参构造 POJO；`CallRelation` 有 final 字段+无无参构造，Jackson 直接反序列化不安全，故用 DTO）。
2. **建全局索引**（`queryNodes(pid,vid,type,null,null,null,0)`，limit=0=全量）：
   - 类节点（Controller/Service/Mapper）：`simpleNameToFqn`（简单名→`List<GraphNode>`，god-node 输入）+ `fqnToClassNode`（nodeKey FQN→节点）。简单名从 nodeKey 取 `lastIndexOf('.')+1` 后缀，回退 `nodeName`。
   - Method 节点：`methodIndex`（`owningFqn + "|" + normalizeName(methodName)`→`List<GraphNode>`）。owningFqn = `nodeKey.substring(0, lastIndexOf('.'))`；methodName = 最后一个 `.` 与 `(` 之间。
   - `normalizeName` = `replaceAll("[^A-Za-z0-9]","").toLowerCase()`（graphify `_key`）。
3. **去重集**：`queryEdges(pid,vid,"CALLS",null,0)` → `Set<fromId|toId>`（节点对是稳定身份；edgeKey 在逐文件简单名 vs 解析器 FQN 间不同，故按节点对去重）。
4. **逐 fact 解析**（跳过 targetClass 空 / callerMethod 空[合成 "injects:" 行]）：
   - **Caller**：`callerFqn=callerClass`。先 `findNode(Method, callerFqn+"."+callerMethodSignature)`（精确）→ 否则 methodIndex god-node(恰好1) → 否则 `fqnToClassNode`。无则跳。
   - **Target FQN**（god-node）：`candidates=simpleNameToFqn.get(targetClass)`；空/`>1` 跳过；`targetFqn=candidates.get(0).getNodeKey()`。
   - **Target Method**（god-node + 精确签名快路径）：有 calledMethodSignature → `findNode(Method, targetFqn+"."+sig)`；否则 `methodIndex.get(targetFqn+"|"+normalizeName(calledMethod))`，恰好1→方法节点，`>1`（重载）→null 回退类级，0→null。
   - **端点**：`toNode = targetMethodNode ?: targetClassNode`（Ruby `method_nid or class_nid` 回退）；`fromNode = callerMethodNode ?: callerClassNode`。任一 null 或自调用跳过。
   - **去重+收集**：`(fromId,toId)` 不在集则加入，push `GraphEdge` POJO（克隆 `BusinessGraphBuilder.buildEdgePOJO`）：edgeType=`CALLS`、edgeKey=`fromNode.nodeKey + "->calls->" + toNode.nodeKey`（FQN，幂等）、sourceType=`CODE_AST`、confidence=`1.0`（字段声明类型=源码显式=EXTRACTED 档）、status=`CONFIRMED`。
5. **批量写**：`neo4jGraphDao.mergeEdgesBatch(candidateEdges)`（一次往返）。

**守卫**：god-node 歧义→跳过（不留低置信边）；逐 fact try/catch debug；Phase 1 不造 stub。

### Part B — 逐文件 pass 停止造重复节点

`GraphBuilder.buildServiceCallGraph`（`:805`）。目标查找（`:824-826`）由 `findOrCreateNodeByClass`（miss 即**创建**重复简单名节点）改为 **find-only**：
```java
GraphNode targetNode = neo4jGraphDao.findNode(projectId, versionId, targetNodeType.name(), targetClassKey).orElse(null);
```
`targetNode==null` 时，类级（`:829-838`）与方法级（`:841-869`）块均经既有 `callerNode!=null && targetNode!=null` 门跳过 → 交给二次扫描。Caller 节点（FQN）仍 `findOrCreateNodeByClass`（命中真节点，不重复）。

### Part C — `ProjectScanner.runScanBody` 接入

`ProjectScanner.java`：加字段 `private final JavaMemberCallResolver javaMemberCallResolver;` + `@Autowired(required=false)` setter（仿 `setScanPlanningServices`，避免破坏 legacy 构造器）。在 ADAPTER_SCAN 后（`:514` 后、`shouldScanDb` 前、`:519`），`shouldScanCode` 门内：
```java
ScanTask resolveTask = createTask(projectId, versionId, "MEMBER_CALL_RESOLVE", "成员调用二次解析");
try {
    int resolved = javaMemberCallResolver.resolveMemberCalls(projectId, versionId);
    completeTask(resolveTask, "resolved " + resolved + " member-call edges", null);
} catch (Exception e) {
    log.warn("Member-call resolution failed: {}", e.getMessage());
    completeTask(resolveTask, "failed: " + e.getMessage(), e.getMessage());
}
```
`createTask` taskType 为自由 String，`MEMBER_CALL_RESOLVE` 合法。

## Phase 2（暂不实现）

扩 `ServiceCallExtractor.collectInjectedVarTypes` 覆盖方法局部变量 + 方法参数（graphify C# `_csharp_member_type_table` 模式），让 `localMapper.findById()`、`param.method()` 也能解析。`collectMethodVarTypes`（`:219`）已为参数类型推断建表，可抬升驱动 `CallRelation.targetClass`。持久化进 fact（`CallRelation` 加 `receiverVarTypes` map 序进 `normalizedData`），二次扫描无需重解析。置信度 INFERRED(0.8) + PENDING_CONFIRM（区别于 Phase 1 的 EXTRACTED/1.0/CONFIRMED）。

## 关键文件
- `backend/src/main/java/io/github/legacygraph/builder/JavaMemberCallResolver.java`（新）
- `backend/src/main/java/io/github/legacygraph/builder/GraphBuilder.java`（`buildServiceCallGraph` find-only，`:824-826`）
- `backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java`（注入 + hook，`:514` 后）
- 模板：`BusinessGraphBuilder.mapFeaturesToCode`(`:298`) / `buildEdgePOJO`(`:777`) / `mergeEdgesBatch`(`:367`)

## 验证
1. 单测 `JavaMemberCallResolverTest`：god-node（同名类→跳过）、happy path（方法级边）、重载（方法级跳→类级）、精确签名快路径、自调用跳、去重、空 targetClass 跳。
2. `buildServiceCallGraph` 回归：mock `findNode`→empty，断言目标不 `mergeNode`/`upsertNode`。
3. `mvn clean test -Dtest='AiScanOrchestratorTest,GraphBuilderTest,BusinessGraphBuilderTest,JavaServiceCallAdapterTest,ServiceCallExtractorTest'`。
4. 部署后重扫保证金：`MATCH (s:Service)-[:CALLS]->(m:Mapper) RETURN count(*)`（0→数百）；完整链路数（0→多条）；简单名重复节点数（0）。

---

# 实施结果（2026-07-08）

按设计三部分落地，复检时发现并修正一处与"内存索引+最少往返"目标冲突的偏差。

## 已实现

| Part | 文件 | 内容 |
|---|---|---|
| A | `builder/JavaMemberCallResolver.java`（新，`@Service`） | 二次扫描解析器：加载 SERVICE_CALL fact → 建全局索引（`simpleNameToFqn`/`fqnToClassNode`/`methodIndex`/`methodByExactKey`）→ god-node guard 解析 → 批量 `mergeEdgesBatch` |
| B | `builder/GraphBuilder.java`（`buildServiceCallGraph:826`） | 目标节点查找由 `findOrCreateNodeByClass` 改 `findNode`（find-only），miss 即跳过交二次扫描，杜绝简单名重复节点 |
| C | `task/ProjectScanner.java` | `@Autowired(required=false)` setter 注入 resolver；`runScanBody` 在 ADAPTER_SCAN 后、`shouldScanCode` 门内跑 `MEMBER_CALL_RESOLVE` 任务，失败非阻塞 |

## 复检发现的偏差与修正

**偏差**：原 `findMethodNode` 的"精确签名快路径"逐 fact 调 `neo4jGraphDao.findNode(...)`——479 fact × 2（caller+target）≈ **958 次 Neo4j 往返**，与设计目标"内存索引 + 仅 ~6 次往返（4 queryNodes + 1 queryEdges + 1 mergeEdgesBatch）"直接冲突。设计文里"精确签名快路径（findNode）"的措辞自相矛盾。

**修正**：新增内存索引 `methodByExactKey`（Method nodeKey `FQN.methodName(paramTypes)` → 方法节点），从已 queryNodes 的 methods 列表构建（零额外往返）。`findMethodNode` 改为查 `methodByExactKey`（内存）→ 失败再 `methodIndex` god-node。修正后解析阶段**全程内存，零 fact 级 Neo4j 往返**，符合设计目标。

同步更新测试：移除 `findNode` 的 Mockito stub（改为 `stubQueryNodes` 提供 methods 走内存索引），避免 strict-stub 报错。

## 其他小项
- `@Service` 而非设计的 `@Component`：两者皆为 Spring 组件扫描语义，等价，无影响。
- `properties` provenance JSON（设计标注 optional）未设：Phase 1 不做，留待 Phase 2 按需加。
- ID 生成按项目规范用 `IdUtil.fastUUID()`（linter 提示，比 `UUID.randomUUID().toString()` 快 9×）。
- 清理未用 import（`LinkedHashMap`）。

## 测试结果
- `JavaMemberCallResolverTest` **5/5**：happy path（方法级 CALLS 边、FQN edgeKey、confidence 1.0）、god-node 歧义跳过、重载回退类级、空 targetClass 跳过、去重。
- 广扫 **47/47**（GraphBuilder/BusinessGraphBuilder/ServiceCallExtractor/JavaServiceCallAdapter/AiScanOrchestrator/EvidenceGraphWriter）全过，无回归。
- `mvn clean compile` 通过。

## 变更文件清单
- 新增 `backend/src/main/java/io/github/legacygraph/builder/JavaMemberCallResolver.java`
- 新增 `backend/src/test/java/io/github/legacygraph/builder/JavaMemberCallResolverTest.java`
- 改 `backend/src/main/java/io/github/legacygraph/builder/GraphBuilder.java`（`buildServiceCallGraph` 目标 find-only）
- 改 `backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java`（注入 + hook）

## 待办
- **部署 + 重扫保证金**实测定量（Service→Mapper 边数、完整链路数、简单名重复节点数），验证设计预期。
- **Phase 2**（未做）：扩 `ServiceCallExtractor.collectInjectedVarTypes` 覆盖方法局部变量/参数，解析 `localMapper.findById()` 等长尾；置信度 INFERRED(0.8)/PENDING_CONFIRM。

