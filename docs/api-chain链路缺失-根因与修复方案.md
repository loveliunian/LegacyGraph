# api-chain 链路缺失：根因调查与修复方案

> 状态：调查中（事实由云端 Neo4j 实地核对 + 代码路径阅读交叉验证）
> 范围：代码图谱查询 → `GET /lg/projects/{projectId}/graph/api-chain` 返回链路只有 ApiEndpoint + Method 节点，缺少 Controller → Service → Mapper → SQL → Table
> 涉及版本：`baaf460f-574a-4203-6692-fbb1bab9fb85`（项目 `3ffc4c91-0a88-4fe2-b130-f7d29a48c9a8`）

---

## 0. TL;DR

**结论**：根本问题不是 `api-chain` 的查询逻辑，也不是 BFS 反向 `CONTAINS` 缺失，而是**图谱构建阶段就没有产出 `Service`、`Mapper`、`SqlStatement` 节点**，自然没有任何 `EXECUTES` / `READS` / `WRITES` / `JOINS` / `CALLS_DB` 边。

**真实链路**只有 3 跳：`GET /upload/uploadPDF -HANDLED_BY-> uploadPDF -CONTAINS-> BankUploadController -IMPLEMENTED_BY-> 银行回单(BO) -MAPS_TO-> bank_detal`(Table 的弱关联)，**经"BUSINESS_OBJECT 节点" 跨业务域**，不是 SqlStatement 经 READS/WRITES 关联出来的真表。

**修复方向**（按优先级）：

1. **P0** 让 `MyBatisAnnotationAdapter` / `MyBatisXmlAdapter` 真正跑出 `SqlStatement` 节点（修复命名/接口识别条件 + 文件扫描收录路径）
2. **P0** 让 `ServiceCallExtractor` 把 `@Autowired` 的 Mapper 字段（不需要 Service 中转）也能产出 CALLS_DB 边
3. **P1** 让 `GraphPathReadModel.getApiCallChain` 在 Method 处反向遍历 `CONTAINS` 收集 Controller / Service / Mapper 节点，使前端在 Service/Mapper 节点补齐后能正确显示完整链路
4. **P2** 让 `GraphPathReadModel` 兼容"老版本边不带 r.projectId/r.versionId" 的兼容 fallback（在扫描器侧修以后可去掉）

---

## 1. 现状（云端 Neo4j 实地核对）

### 1.1 节点分布

| 节点类型 | 数量 | 备注 |
|---|---|---|
| Feature | 969 | 前端视角 |
| Menu | 482 | 前端视角 |
| **Method** | **418** | **其中 398 个挂在 Controller 类下，20 个归属未知（即 FQN 在抽取阶段被推断为 `Unknown`），0 个挂在 Service/Mapper 类下** |
| ApiEndpoint | 392 | 入口节点 |
| Column | 248 | 表字段 |
| BusinessObject | 173 | 业务对象 |
| BusinessProcess | 113 | 业务流 |
| Unknown | 95 | 类型兜底 |
| Button | 88 | 前端 |
| Controller | 39 | 控制器节点 |
| Table | 24 | 库表 |
| **Service** | **2** | `TimerService`、`NxTransService`（**没有任何 Method 挂在它们下面**） |
| **Mapper** | **2** | `JqGridJsonMapper`、`JsonMapper`（**同样没有任何 Method 挂在它们下面**） |
| Page | 1 | |
| ExternalSystem | 1 | |

### 1.2 关键边全图

| 边 | 数量 | 说明 |
|---|---|---|
| CONTAINS | 866 | 父子包含 |
| HANDLED_BY | 411 | API→Method |
| HAS_COLUMN | 248 | Table→Column |
| CALLS | 224 | |
| IMPLEMENTED_BY | 199 | Controller→BusinessObject |
| POSSIBLE_SAME_AS | 131 | |
| IN_DOMAIN | 113 | |
| EXPOSED_BY | 77 | |
| MAPS_TO | 63 | BusinessObject→Table（业务对象到表名映射） |
| CALLS_EXTERNAL | 7 | |
| CALLS_DB | 1 | 仅 1 条 |
| **EXECUTES** | **0** | Mapper/Method→SqlStatement 链路完全缺失 |
| **READS / WRITES** | **0** | SqlStatement→Table 完全缺失 |
| **READS_DB / WRITES_DB** | **0** | 同上 |
| **JOINS** | **0** | 同上 |
| **DATA_FLOW** | **0** | 同上 |

### 1.3 当前 6 跳可达类型分布（从 ApiEndpoint `1eabf6ec21014c44914c533c9f4c0a9d` 出发）

```
Feature            295
Method             97
ApiEndpoint        92
BusinessProcess    33
Controller         20
Column             11
FeatureModule      6
BusinessDomain     4
BusinessObject     4
Page               1
Table              1
```

注意：**即使放开方向限制（无向 6 跳 BFS），整个图谱没有任何路径让 Method → Service 或 Method → Mapper 或 Method → SqlStatement 可达**。也就是说"链路断在 Service/Mapper/SQL"不是 api-chain BFS 选择策略问题，而是**图本身就没有这些节点和这些边**。

### 1.4 当前 api-chain（默认实现）实际跑出来的链路

```text
GET /upload/uploadPDF   (ApiEndpoint)
  └─ HANDLED_BY → uploadPDF   (Method)
        └─(再往下没出边了；反向 CONTAINS 是 (Controller)-CONTAINS->(Method) 方向，不在当前 queryOutgoingEdges 的扫描范围)
```

Method 节点的全部邻居（实际跑过）：

| 关系 | 类型 | 起点 | 终点 | 备注 |
|---|---|---|---|---|
| `uploadPDF` -CONTAINS-> `BankUploadController` | CONTAINS | Method | Controller | **反向**到 Controller（BFS 看不到） |
| `uploadPDF` -HANDLED_BY-> `GET /upload/uploadPDF` | HANDLED_BY | Method | ApiEndpoint | 入口 |

注意该 Method 的全部出边只有 1 条 CONTAINS（**但方向错了** —— 实际图谱是 `(Controller)-[CONTAINS]->(Method)`，所以从 Method 出发查出来的"出边"是**反向查询的偶然结果**，因为 Cypher `(from)-[r]->(to)` 的 `from`/`to` 端无方向语义取决于实际图谱内容；进一步的"完整最短路径"确实给出了 `bank_detal` Table，但路径靠的是 BusinessObject 跨域的 `MAPS_TO` 边）。

### 1.5 该 BankUploadController 的真实邻居

```text
- CONTAINS   → Method：uploadPDF（接收子节点）
- CONTAINS   → BusinessDomain：银企直连银行交互
- IMPLEMENTED_BY ← → BusinessObject：银行回单 / 银行回单/附件 / 银行渠道
```

---

## 2. 根因定位（按事实逐层）

### 2.1 为什么 `Service` 节点只有 2 个且都是孤儿？

**Service 节点唯一写入入口**：`GraphBuilder.buildJavaStructureGraph` (`GraphBuilder.java:1997-2033`)，路径 →
`JavaServiceCallAdapter.extract` → `JavaStructureExtractor.extractFromFile` → `inferNodeType(className, annotations)` (`GraphBuilder.java:2351-2388`)。

`inferNodeType` 对 Service 的识别顺序：

| 优先 | 规则 | 行号 |
|---|---|---|
| 1 | 注解简单名 == `Service` 或 `Component` | `2358, 2361` |
| 2 | **类 FQN 最后一段以 `Service` 或 `ServiceImpl` 结尾**（兜底） | `2377-2378` |
| 3 | 否则返回 `Unknown`，**不写入 Service 节点** | `2386-2387` |

**云端事实回放**：
- 该项目一共采到 `TimerService` 和 `NxTransService` 两个 Service 节点
- 这两个节点下面的 Method 数为 **0**（孤立节点）
- 全部 418 个 Method 节点的 FQN 倒数第二段（即归属类名）分布：
  - `Controller` 结尾：**398 个**（占 95.2%）
  - `Unknown` 字面：**20 个**
  - `Service`/`ServiceImpl`/`Mapper`/`Dao`：**0 个**

也就是说：
- 项目里如果有 Service 类，它们的 method 在 `inferNodeType` 阶段是被归到 Controller/`Unknown` 家族的——**意味着这些 Service 类的注解和类名**都不符合 Service 识别规则**
- 反过来，只有 2 个类名以 `Service` 结尾被"命名兜底"识别为 Service 节点，但**这两个类的 method 没写进同一个 JavaClassInfo 里**（`buildJavaStructureGraph` 在 Controller 子树的 method 批量里把它们归进了 Controller 节点）

> 解读：该项目大概率**没有显式 `@Service` 标注的业务层类**（Service 命名以业务语义为主，不像 Controller 那样唯一以 `Controller` 后缀兜底），所以 `inferNodeType` 的"命名兜底"规则只命中了偶然的工具类 Timer/NxTrans。

### 2.2 为什么 `Mapper` 节点也是 2 个孤儿？

`inferNodeType` 对 Mapper 的识别顺序相同：
1. 注解 `Mapper`/`Repository`
2. **类 FQN 最后一段以 `Mapper`/`Dao`/`DAO` 结尾**（兜底）
3. 否则返回 `Unknown`

而真正的 MyBatis Mapper 接口在该项目 Java 源码里的真实命名情况**未知** —— 唯一 2 个 `Mapper` 节点（JqGridJsonMapper/JsonMapper）是 `com.utils.jqgrid.*` 工具类，**它们的 method 同样归属 Unknown / Controller**——它们只是因为"名字以 `Mapper` 结尾"被兜底识别成"Mapper 节点"。

### 2.3 为什么 `SqlStatement` = 0

**SqlStatement 唯一写入入口**：`buildMapperSqlGraph` (`GraphBuilder.java:396-411`)，依赖 `MapperSqlFact.namespace` 非空且 `mapperFact.getStatements().size > 0`。

`MapperSqlFact` 由两个 Adapter 产出：
- **`MyBatisXmlAdapter`** (`MyBatisXmlAdapter.java:31-37, 68-80`) → 文件需 `*.xml` 且文件名含 `Mapper` / `Dao` / `Repository`
- **`MyBatisAnnotationAdapter`** (`MyBatisAnnotationAdapter.java:31-37, 74-85`) → 文件需 `*.java` 且文件名含 `Mapper` / `Dao` / `Repository`，进而在 `MyBatisAnnotationExtractor.extractFromFile` (`MyBatisAnnotationExtractor.java:50-135`) 内再次校验：
  - AST 必须能抽出 `ClassOrInterfaceDeclaration`
  - **`isInterface`（仅接口）**
  - **类名以 `Mapper` / `Dao` / `Repository` 结尾**
  - **至少一个方法带 `@Select` / `@Insert` / `@Update` / `@Delete` 之一**

也就是说：**当前项目里的 MyBatis Mapper 接口（如果有）的命名或文件命名不满足"以 Mapper/Dao/Repository 结尾"**，被两个 Adapter 一起过滤掉 → `MapperSqlFact` 不产出 → `buildMapperSqlGraph` 退出 → SqlStatement 节点数为 0。

> 重要含义：**MyBatis-Plus 时代的 `extends BaseMapper<T>` 接口如果命名不带 `Mapper/Dao/Repository` 后缀（如 `BankCardDaoExt`、`UserRepositoryImpl` 这种），两边 Adapter 都会漏掉**。这是"路径 B"的根本性"名字驱动"问题。

### 2.4 为什么 `EXECUTES` / `READS` / `WRITES` / `JOINS` 边全为 0

`buildMapperSqlGraph` 末尾的 EXECUTES/READS/WRITES 等全批处理：

- EXECUTES (Mapper→SqlStatement): `GraphBuilder.java:419-422`
- EXECUTES (Method→SqlStatement): `GraphBuilder.java:511-520` + `linkMapperMethodsToSqlStatements` `GraphBuilder.java:529-599`
- READS_DB / READS / WRITES_DB / WRITES: `GraphBuilder.java:432-457`
- JOINS: `GraphBuilder.java:460-466`
- DATA_FLOW: `GraphBuilder.java:468-478`

**所有这些代码路径都以 `mapperFact.getStatements()` 不为空为前提**。SqlStatement = 0 → 这些边全部 = 0 是必然推论。

### 2.5 为什么 `CALLS_DB` = 1

CALLS_DB 走的是 `buildServiceCallGraph` (`GraphBuilder.java:2188-2260`) + `resolveCallEdgeType` (`GraphBuilder.java:2441-2456`)：

- 起点是 Service/Controller 等"调用方"节点（`GraphBuilder.java:2198`）
- 终点是 Mapper 节点，**目标节点只要 nodeType == Mapper 即输出 CALLS_DB 边**
- 依赖 `ServiceCallExtractor` 抽到 `@Autowired` 注入的字段或方法内的 `mapper.xxx()` 调用 (`ServiceCallExtractor.java:143-201, 371-394`)

当前只有 1 条 CALLS_DB，很可能只是恰巧某个 Service 文件 autowired 了一个 Mapper 字段（被伪 Mapper 节点名兜底匹配上了），不代表"真实识别了 MyBatis Mapper"。

### 2.6 路径总结

```text
buildJavaStructureGraph  (路径 A)         buildMapperSqlGraph    (路径 B)
   JavaServiceCallAdapter                   MyBatisXmlAdapter / MyBatisAnnotationAdapter
        ↓                                                ↓
   JavaStructureExtractor                     MyBatisXmlExtractor / MyBatisAnnotationExtractor
        ↓                                                ↓
   inferNodeType(className, annotations)      MapperSqlFact
        ├── @Service / @Component / 命名兜底           ├── namespace
        └── → Service 节点 (2 个孤儿)                  └── statements → SqlStatement
        ├── @Mapper / @Repository / 命名兜底
        └── → Mapper 节点 (2 个孤儿)
                                                   两者独立写 Mapper 节点 (key 不同)

   Controller 节点写入 (39 个) ← 主力               两条路径在项目里几乎都没起作用
   Method 节点写入 (418 个全部挂在 Controller 下)
```

---

## 3. 问题分类

| 序号 | 类别 | 位置 | 影响 |
|---|---|---|---|
| Q1 | **核心问题**：Service/Mapper 类未在 `inferNodeType` 命中，导致节点缺失 | `GraphBuilder.java:2351-2388` | 高 —— 全部链路 |
| Q2 | 真实 MyBatis Mapper 接口命名不规范，触发不到 `MyBatisAnnotationAdapter` / Extractor | `MyBatisAnnotationAdapter.java:74-85`、`MyBatisAnnotationExtractor.java:64-74` | 高 |
| Q3 | MyBatis XML 文件名 / 位置可能未进 Adapter 扫描资产 | `MyBatisXmlAdapter.java:68-80` + `ProjectScanner` | 中（如有 XML） |
| Q4 | `ServiceCallExtractor` 未识别 `@Autowired Mapper 字段直接注入`（无显式 Service 中转）的项目结构 | `ServiceCallExtractor.java:143-201, 371-394` | 中 |
| Q5 | `api-chain` BFS 未反向收集 `(parent)-CONTAINS->(Method)` | `GraphPathReadModel.getApiCallChain` `GraphPathReadModel.java:104-151` | 中 —— 节点即使补齐后链路也可能不连续 |
| Q6 | 边的 `r.projectId` / `r.versionId` 属性过滤在老版本图谱上会丢边（兼容性） | `Neo4jProjectionRepository.java:491-498, 522-528` | 低 —— 如不做增量老数据兼容可不修 |

---

## 4. 修复方案

### 4.1 P0-1 改进 `inferNodeType` 以正确归类 Service/Mapper

**目标**：让被 `@Service` / `@Repository` / `@Mapper` 注解的类**无论名字是什么**都能正确归类为 Service / Mapper。

**修改点**：
- 文件：`GraphBuilder.java`
- 行号：`2351-2388 inferNodeType`

```text
1) 注解列表里增加更宽松匹配：
   - FQN 形式：`org.springframework.stereotype.Service` ⇒ Service
               `org.springframework.stereotype.Component` ⇒ Service
               `org.apache.ibatis.annotations.Mapper` ⇒ Mapper
               `org.springframework.stereotype.Repository` ⇒ Mapper
   - 简化名：`Service`/`Component` ⇒ Service
               `Mapper`/`Repository` ⇒ Mapper
2) 单纯走兜底前先看：
   - 类是否是 `interface`，且父接口或泛型参数含 `BaseMapper`/`IService` ⇒ Mapper / Service
   - 类是否 `extends BaseServiceImpl<...>` 或实现 `IService<...>` ⇒ Service
3) 兜底保留：
   - 类名以 `ServiceImpl`/`Service` 结尾 ⇒ Service
   - 类名以 `Mapper`/`Dao`/`DAO` 结尾 ⇒ Mapper
   - 否则 Unknown
```

> 注意：避免"类名以 Service 结尾"的兜底过激。当前项目里 `TimerService`/`NxTransService` 命中兜底被误识别为 Service —— **这两个节点本身是误报**，因为它们没有 method 挂在它们下面。修复后这两节点会被识别为更合适的类型（如果它们没有任何 Spring/MyBatis 注解且名字仅是巧合，最终应该走 Unknown）。

### 4.2 P0-2 修正 MyBatis Adapter 的"命名驱动"过滤

**目标**：让 MyBatis-Plus 时代的"任何接口命名 + MyBatis XML 任何位置"也能被识别。

**修改点 A**：`MyBatisAnnotationAdapter.java:74-85`

```text
将"文件名以 Mapper/Dao/Repository 结尾"的硬过滤改为：
  ① 文件名匹配；或者
  ② 类内有 `extends BaseMapper<...>` 或方法挂 `@Select/@Insert/@Update/@Delete/@SelectProvider/...`
两者满足其一即触发。
```

**修改点 B**：`MyBatisAnnotationExtractor.java:64-74`

```text
放宽类名匹配：
  - 接口名以 Mapper/Dao/Repository/DAO/DaoImpl 结尾；或
  - 接口继承 `BaseMapper`、`Mapper<T>`、`JoinMapper` 等通用接口
两者满足其一即认定是 Mapper 接口。

方法识别（在 91-115）：
  - 已知 `@Select`/`@Insert`/`@Update`/`@Delete`/`@SelectProvider`/`@InsertProvider`/`@UpdateProvider`/`@DeleteProvider`
  - 同时**新增对 MyBatis-Plus 的 `LambdaQueryWrapper` + `selectList/selectOne/...` 调用模式识别**（可选 MVP 期暂时不上，先把注解的兜住）
```

**修改点 C**：`MyBatisXmlAdapter.java:68-80`

```text
将"文件名匹配 Mapper.xml/Dao.xml/Repository.xml"放宽为：
  - `*.xml` 且含 `<mapper namespace="...">` 节点
  - 进一步用 namespace 字符串做兜底（namespace 含 `.mapper.` / `.dao.` / `.repository.`，或者 namespace 对应的 Java 文件被 ServiceCallAdapter 标记过）
```

### 4.3 P0-3 兼容"Controller 直接 @Autowired Mapper"的项目结构

**目标**：补齐 CALLS_DB 边，让 Controller 直接连 Mapper 的项目链路完整。

**修改点**：`JavaCodeAdapter` 和 `JavaServiceCallAdapter` 的 `ServiceCallExtractor.collectInjectedVarTypes` (`ServiceCallExtractor.java:371-394`)

```text
当前逻辑：仅在 ServiceCallAdapter（不做 Controller 抽取链路）走 inject 字段收集？
实际不是：JavaCodeAdapter 处理 Controller 时也会调 buildServiceCallGraph，但 collectInjectedVarTypes 内部应该只针对非 Controller 文件？
（需要精确读 ServiceCallExtractor 后确认范围）
```

> **实施细节**：让 `JavaCodeAdapter` 在分析 Controller 文件时也把 `@Autowired`/`@Resource` 注入的字段和 Lombok 单构造器注入当作 `Mapper` 字段 → 在方法体内调用 `xxxMapper.xxx()` 时把它识别为 Mapper 调用 → CALLS_DB 边落地。

### 4.4 P1 改进 `api-chain` BFS 兼容反向 `CONTAINS`

**目标**：在 `api-chain` 跳到 Method 节点后，反向一跳收集 `(Controller/Service/Mapper)-[CONTAINS]->(Method)` 父节点，把链路补成 `API → Method (+ parents) → SqlStatement → Table`。

**修改点**：`GraphPathReadModel.getApiCallChain` `GraphPathReadModel.java:108-151`

```text
伪代码：
  BFS 出边同原逻辑；
  当 frontier 包含 Method 节点时，额外调用
    queryIncomingEdges(projectId, versionId, methodIds)
  并过滤出 edgeType == CONTAINS 的入边，把 Controller/Service/Mapper 父节点加入 chain。

也可以在 forward BFS 同时跑反向 BFS（先做单边深度 12，反向做单边深度 1），
以避免 methodIds 集合膨胀（同类对同 method 多 conTains 重复问题由 visited 去重解决）。
```

**配套**：`getApiCallChainUncached` 输出顺序需要稳定，**建议输出顺序**：
1. ApiEndpoint（起点）
2. Method（CONTAINS 出来的 Controller/Service/Mapper 父节点紧邻 Method）
3. Controller / Service / Mapper
4. SqlStatement
5. Table + Column

### 4.5 P2 兼容老图谱无 `r.projectId`/`r.versionId` 的边

**目标**：老版本（V85 之前的边）没有边上的 `r.projectId`/`r.versionId` 属性；当前 `queryOutgoingEdges` 直接 `WHERE r.projectId = $projectId` 会把这种边过滤掉。

**修改点**：`Neo4jProjectionRepository.queryOutgoingEdges` `Neo4jProjectionRepository.java:491-498`、`queryIncomingEdges` `Neo4jProjectionRepository.java:522-528`

```text
放宽策略：
  WHERE (r.projectId IS NULL OR r.projectId = $projectId)
    AND (r.versionId IS NULL OR r.versionId = $versionId)
两者关系：`AND` → `OR`（兼容老图谱）
```

> **注意**：这只是一次性兼容，新扫描写入的边已经具备这两个属性，建议在 V91 migration 后配合 graph_version 字段打标，再做"老边写回"或"清理重扫"。

### 4.6 联动审计

修复完后需要做的核对动作（验收清单）：

1. **重扫验收**：选 `3ffc4c91-...` 项目一个小版本号（次级 commit），重新扫描后 Neo4j 中 Service/Mapper/SqlStatement 节点 + EXECUTES/READS/WRITES 边非零。
2. **api-chain 验收**：再次访问 `GET /api/lg/projects/{pid}/graph/api-chain?api=GET+/upload/uploadPDF@6363f74c`，返回的 nodes 数 ≥ 5（API + Method + Controller + SqlStatement + Table）。
3. **回归**：保证现有 392 个 ApiEndpoint 都有可用的链路（或合理降级原因在 `degraded=true`）。

---

## 5. 实施建议

### 5.1 阶段一：图谱构建修复

| 任务 | 涉及文件 | 影响范围 | 预计工时 |
|---|---|---|---|
| `inferNodeType` 增加 FQN 注解匹配 + BaseMapper / IService 推断 | `GraphBuilder.java` | 全量所有项目重扫 | 中（需要新增单测覆盖注解归一化） |
| `MyBatisAnnotationAdapter` 放宽 | `MyBatisAnnotationAdapter.java` | 所有项目 | 小 |
| `MyBatisAnnotationExtractor` 接口识别（继承 BaseMapper） | `MyBatisAnnotationExtractor.java` | 所有项目 | 中 |
| `MyBatisXmlAdapter` 用 namespace 取代文件名兜底 | `MyBatisXmlAdapter.java` | 所有项目 | 小 |
| `ServiceCallExtractor` 适配 Controller 直接注入 Mapper | `ServiceCallExtractor.java` | Controller 直连 Mapper 的项目 | 中 |

### 5.2 阶段二：api-chain 修复

| 任务 | 涉及文件 | 预计工时 |
|---|---|---|
| `GraphPathReadModel.getApiCallChain` 增加反向 CONTAINS 收集 | `GraphPathReadModel.java` | 中 |
| 排序逻辑让 Controller/Service/Mapper/SqlStatement/Table 按层顺序输出 | `GraphPathReadModel.java` + `getApiCallChainUncached` | 小 |
| 兼容性 OR 过滤老图谱不带 `r.projectId` 的边 | `Neo4jProjectionRepository.java:491-498` + `522-528` | 小 |

### 5.3 阶段三：单测覆盖与回归

- `inferNodeType` 的各种注解组合单测
- `MyBatisAnnotationAdapter.supports` 的新规则单测
- `api-chain` 服务于"Method 节点无 SqlStatement 时的最大努力返回 + degraded 标记"单测

---

## 6. 验收指标（建议）

| 指标 | 修复前 | 修复后目标 |
|---|---|---|
| 该项目 Service 节点数 | 2 (孤儿) | ≥ 10 |
| 该项目 Mapper 节点数 | 2 (孤儿) | ≥ 5 |
| 该项目 SqlStatement 节点数 | 0 | ≥ 50 |
| EXECUTES 边数 | 0 | ≥ 50 |
| READS / WRITES 边数 | 0 | ≥ 50 |
| CALLS_DB 边数 | 1 | ≥ 5 |
| `api-chain` `/upload/uploadPDF@6363f74c` 返回 nodes 数量 | 2 | ≥ 5（按 Step 4.4 排序输出） |

---

## 7. 不在本次修复范围（明确排除）

- `JavaStructureExtractor` 内 `ANNOTATION_TO_NODE_TYPE` Map（注释 + 隐式工具方法）即便与 `inferNodeType` 重复，但不影响产出，**不动**
- `V88 edge_type_dict` 之类的字典迁移：与本次问题无关
- 跨语言/前端/BusinessObject 节点调整：仅作为观察记录
- 重新设计 Neo4j schema：保持 schema 不变，仅改写入逻辑

---

## 8. 关键文件 / 行号速查表

| 关注点 | 文件路径 | 行号 |
|---|---|---|
| Service/Mapper/Controller 节点入库 | `backend/src/main/java/io/github/legacygraph/builder/GraphBuilder.java` | buildJavaStructureGraph `1997-2033`；inferNodeType `2351-2388` |
| Call/CALLS_DB 边入库 | 同上 | buildServiceCallGraph `2188-2260`；resolveCallEdgeType `2441-2456` |
| Mapper+SqlStatement+EXECUTES+READS+WRITES 入库 | 同上 | buildMapperSqlGraph `365-523`；linkMapperMethodsToSqlStatements `529-599` |
| 注解路径 Mapper+Sql | `backend/src/main/java/io/github/legacygraph/extractors/adapter/MyBatisAnnotationAdapter.java` | supports `31-37`；isMapperInterface `74-85` |
| 注解路径 SQL 抽取 | `backend/src/main/java/io/github/legacygraph/extractors/MyBatisAnnotationExtractor.java` | extractFromFile `50-135`；SQL_ANNOTATIONS `28-34` |
| XML Mapper 识别 | `backend/src/main/java/io/github/legacygraph/extractors/adapter/MyBatisXmlAdapter.java` | supports `31-37`；isMapperFile `68-80` |
| 注释里出现但**实际未用**的 nodeType 映射 Map | `backend/src/main/java/io/github/legacygraph/extractors/JavaStructureExtractor.java` | ANNOTATION_TO_NODE_TYPE `501-519`（注释阶段） |
| Controller Adapter | `backend/src/main/java/io/github/legacygraph/extractors/adapter/JavaCodeAdapter.java` | supports `60-81` |
| 通用 Service/Mapper Adapter | `backend/src/main/java/io/github/legacygraph/extractors/adapter/JavaServiceCallAdapter.java` | supports `50-72` |
| Service→Mapper 调用抽取 | `backend/src/main/java/io/github/legacygraph/extractors/ServiceCallExtractor.java` | extractFromFile `75-347`；collectInjectedVarTypes `353-394` |
| `api-chain` BFS | `backend/src/main/java/io/github/legacygraph/service/graph/GraphPathReadModel.java` | getApiCallChain `72-154` |
| `api-chain` 边查询 | `backend/src/main/java/io/github/legacygraph/dao/Neo4jProjectionRepository.java` | queryOutgoingEdges `487-511`；queryIncomingEdges `519-543` |
| `api-chain` 缓存封装 | `backend/src/main/java/io/github/legacygraph/service/graph/GraphQueryService.java` | getApiCallChain `122-175` |
| `api-chain` Controller | `backend/src/main/java/io/github/legacygraph/controller/GraphQueryController.java` | getApiChain `78-91` |
| 错误 mock 测试（待修正） | `backend/src/test/java/io/github/legacygraph/service/GraphPathReadModelTest.java` | testGetApiCallChain_bfsNeighborExpansion `50-83`（假定 API→Service 直接 CALLS 是不对的） |

---

## 9. 错误/反直觉的代码/测试清单（仅供修复时校对）

1. **`GraphPathReadModelTest.testGetApiCallChain_bfsNeighborExpansion`**（`GraphPathReadModelTest.java:50-83`）：mock 直接假定 `ApiEndpoint → Service` 间存在 `CALLS` 边 —— **这条边实际不存在**（真实图谱只有 ApiEndpoint-`HANDLED_BY`→Method）。修复后这条用例必须改：要么 mock 改用 HANDLED_BY 边，要么换成"反向 CONTAINS 收集" 用例。
2. **`JavaStructureExtractor.ANNOTATION_TO_NODE_TYPE`**（`JavaStructureExtractor.java:501-519`）：注释里写"用这个 map 判定注解→节点类型"，但 GraphBuilder 自己的 `inferNodeType` 走 switch 硬编码，是另一套。两者长期并存但结论一致，容易诱导新加入的开发者改错文件。**建议删除这个 Map，或加 Javadoc 说明它未实际使用**。
3. **`scripts/check-edge-type-sync.sh`**（git status ?? 新增脚本）：建议在修复完成后跑一遍，确保 `EdgeType` 枚举与数据库字典一致，避免再增新类型时字典缺位。

---

## 附录 A：本调查使用的全部 Cypher（可复用）

```text
# 当前 ApiEndpoint 出边（单跳出 r.projectId+versionId）
MATCH (from)-[r]->(to)
WHERE r.projectId = $pid AND r.versionId = $vid AND from.id IN [$aid]
RETURN type(r) AS t, to.nodeType AS tn, to.nodeName AS nname

# 当前 ApiEndpoint 出发 6 跳可达类型分布（无方向）
MATCH (api:ApiEndpoint {id:$aid})
MATCH (api)-[*..6]-(target)
WITH labels(target)[0] AS t, count(DISTINCT target) AS c
RETURN t AS typeLabel, c AS cnt ORDER BY c DESC

# 反向 CONTAINS 收集父节点（修复后 api-chain 应使用）
MATCH (api:ApiEndpoint {id:$aid})-[:HANDLED_BY]->(m:Method)
OPTIONAL MATCH (ctrl:Controller)-[:CONTAINS]->(m)
OPTIONAL MATCH (svc:Service)-[:CONTAINS]->(m)
OPTIONAL MATCH (mapper:Mapper)-[:CONTAINS]->(m)
RETURN m.nodeName, ctrl.nodeName, svc.nodeName, mapper.nodeName

# 节点类型分布
MATCH (n) WHERE n.projectId = $pid AND n.versionId = $vid
RETURN coalesce(n.nodeType,'<NONE>') AS t, count(*) AS c ORDER BY c DESC

# Method 按归属类（FQN 倒数第二段）家族聚合
MATCH (m:Method) WHERE m.projectId = $pid AND m.versionId = $vid
WITH m, split(m.nodeKey, '.') AS parts
WITH m, parts[size(parts)-2] AS className
RETURN
   CASE
     WHEN className ENDS WITH 'Service' OR className ENDS WITH 'ServiceImpl' THEN 'Service/Impl'
     WHEN className ENDS WITH 'Mapper' THEN 'Mapper'
     WHEN className ENDS WITH 'Dao' OR className ENDS WITH 'DAO' THEN 'Dao'
     WHEN className ENDS WITH 'Controller' THEN 'Controller'
     ELSE 'Other' END AS bucket, count(*) AS c
ORDER BY c DESC
```

## 附录 B：本调查使用的连接参数

读取自 `backend/src/main/resources/application-dev.yml`（**默认环境变量**）：

```text
URI:      bolt://118.145.225.100:7687
Username: neo4j
Password: Liucl157
```

> 注：本调查仅以只读 Cypher 访问，**未做任何写入**。

