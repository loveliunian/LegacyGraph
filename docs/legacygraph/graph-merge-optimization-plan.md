# 业务域节点合并优化方案

> 适用版本：LegacyGraph 当前主分支
> 适用范围：`BusinessDomain` / `BusinessProcess` / `BusinessObject` / `Rule` / `Role` 等业务域类型
> 关联文件：`backend/.../service/graph/GraphMergeService.java`、`agent/GraphMergeAgent.java`、`config/AgentConfigProperties.java`

---

## 一、现状与根因分析

### 1.1 现有合并链路（已具备能力）

`GraphMergeService` 已经具备相对完整的合并基础设施：

| 能力 | 现状 | 文件位置 |
|---|---|---|
| Blocking 避免 O(n²) | `(nodeType + normalized name 前 3 字符)` | `GraphMergeService.java:85-91` |
| 5 维评分 | name(0.35) + struct(0.25) + evidence(0.20) + runtime(0.10) + history(0.10) | `GraphMergeService.java:154-161` |
| 三档决策 | AUTO_MERGE ≥ 0.85 / REVIEW 0.50-0.85 / REJECT < 0.50 | `GraphMergeService.java:273-287` |
| 边重写 + lineage | `properties.mergedFrom[]` + 缓存清理 | `GraphMergeService.java:300-325` |
| LLM 仲裁 | `GraphMergeAgent.decideMerge` 留有 5 维参数位（含 `semanticScore`）但**未自动计算** | `agent/GraphMergeAgent.java` |
| 跨语言合并支撑 | `Neo4jGraphDao.moveEdgesAndDeleteNode` 已就绪 | `dao/Neo4jGraphDao.java:295` |

### 1.2 业务域节点合并失败的根本原因

针对业务域节点的合并失败，定位到 **4 个结构性短板**：

| # | 短板 | 现象 |
|---|---|---|
| **①** | **Blocking 对中文低效** | `normalizeForBlocking` 用 `[^a-z0-9]` 剔除所有非字母数字 → 中文全归一为空串，block 退化为"全部塞进同一 block"，50 邻居滑动补救（行 109-122）形同虚设 |
| **②** | **名称 LCS 无法识别语义等价** | "用户中心" vs "会员管理" vs "账号域" → LCS≈0，但语义同义。LCS 只覆盖字符级同形 |
| **③** | **评分维度缺失"语义相似度"** | 5 维里没有 embedding。`GraphMergeAgent.decideMerge` 的 `semanticScore` 参数已预留但 `GraphMergeService.scoreCandidate` 未填充 |
| **④** | **`aliasNames` 字段已声明未启用** | `GraphNode.aliasNames` 在实体类里但 `EvidenceGraphWriter.upsertNode` 从未写入，跨源别名（中文名/英文名/缩写）丢失，blocking 无法合并 |

### 1.3 业务域与其他类型的本质差异

| 维度 | API/Method/Table | **BusinessDomain/Process/Object/Rule** |
|---|---|---|
| 来源 | 代码 AST（强结构） | **DOC_AI / AI_INFERENCE（弱结构）** |
| 命名 | 驼峰/下划线规范 | **中文自然语言 + 多种叫法** |
| 判重信号 | `nodeKey`（已是内容寻址） | **`description` + `displayName` + 多别名** |
| 合并置信 | AST 路径 + 调用关系 | **语义等价 + 业务归属** |

**结论**：现有 5 维评分天然偏向 API/Method/Table，对业务域节点需要**专门一组"语义优先"的评分维度**，且要改造 Blocking 与归一化。

---

## 二、目标与设计原则

### 2.1 目标

1. **合并召回率**：业务域节点的合并召回率 ≥ 85%
2. **合并精度**：AUTO_MERGE 的误并率 ≤ 5%（人工抽样验证）
3. **成本可控**：单 project 全量合并 LLM 调用 ≤ 200 次（日均）
4. **可回滚**：合并 lineage 完整、可一键回滚

### 2.2 设计原则

- **不破坏现有架构**：`GraphMergeService` 是合并的唯一入口，新增能力都以"新维度 / 新算法"形式扩展
- **类型感知**：业务域走"语义优先"通道，API/Method 走"结构优先"通道（已实现）
- **Blocking 是性能关键**：业务域必须用 MinHash/embedding 双 blocking，O(n) 候选
- **LLM 只仲裁不确定**：>0.85 直接 AUTO，<0.50 直接 REJECT，中间走 LLM + 缓存

---

## 三、架构级优化（5 个核心改进）

### 改进 ①：Chinese-aware 双 Blocking（性能层）

**改动**：`GraphMergeService.findMergeCandidates` 的 Blocking 改为**三通道**：

```
Channel A (字符级)    : normalizeForBlocking 前 N 字符 + nodeType   ← 现有
Channel B (n-gram)    : 字符 bigram MinHash, 阈值 0.3                ← 新增
Channel C (embedding) : 向量 ANN Top-K=50                            ← 新增
```

**关键实现**（新增 `NodeBlockingService`）：

```java
public class NodeBlockingService {
    // 中文双通道：bigram + 首字符拼音首字母
    public String blockKeyChinese(String name) {
        // "用户管理" → bigram {"用户","户管","管理"} → MD5
        // 同时抽 首字符 "用" + 首字拼音首字母 'y'
    }
}
```

**性能预期**：500 节点从 500² 降到 500 × 50（每节点 Top-50 候选）。

---

### 改进 ②：激活 `aliasNames` + 类型感知归一化（数据层）

**问题**：`GraphNode.aliasNames` 字段是空的，`EvidenceGraphWriter.upsertNode` 没写。

**改动**：

1. **`EvidenceGraphWriter.upsertNode`**：写入前从 `GraphNodeClaim.aliasNames` 透传，缺省时从 `nodeName/displayName` 自动派生 2-3 个变体
2. **`GraphBuilder`**：在 `findOrCreateNode` 调用前，业务域类型走 `BusinessDomainNormalizer` 派生别名（中文/英文/拼音首字母）
3. **`Neo4jGraphDao`**：把 `(projectId, versionId, nodeType, aliasNames)` 加入候选查询的过滤条件

**新增 `BusinessDomainNormalizer`**：

```java
public class BusinessDomainNormalizer {
    // "用户中心" → ["用户中心","用户管理","会员","账号","yh","user"]
    public List<String> deriveAliases(String name) {
        // 1. 原文
        // 2. 拼音首字母（小鹤双拼）
        // 3. 关键词拆分（去掉"中心/管理/服务/域"等业务域常用后缀）
        // 4. 同义词词典替换（账号→用户→会员）
    }
}
```

**效果**：blocking 召回率从 30% 提到 70%。

---

### 改进 ③：新增"语义相似度"评分维度（语义层）

**问题**：`GraphMergeAgent.decideMerge` 已预留 `semanticScore` 参数，但 `GraphMergeService.scoreCandidate` 没算。

**改动**：

1. **新增 `SemanticSimilarityCalculator`**：

```java
public class SemanticSimilarityCalculator {
    // 使用已存在的 SemanticCache + vector.api
    public double compute(GraphNode a, GraphNode b) {
        String textA = buildText(a);  // name + displayName + description + aliases
        String textB = buildText(b);
        return vectorApi.cosine(embed(textA), embed(textB));  // 已有 LLM Gateway 复用
    }
}
```

2. **`GraphMergeService.scoreCandidate` 接入**：

```java
// 在 5 维基础上增加第 6 维
c.setSemanticScore(semanticSim.compute(nodeA, nodeB));

// 权重配置（合并到 AgentConfigProperties.MergeConfig）
semanticWeight = 0.25   // 业务域专用通道
// 业务域类型时权重自动调整为：name=0.15 + semantic=0.45 + struct=0.10 + ...
// 非业务域维持现状
```

3. **缓存**：`SemanticCache`（已存在）按 `(nodeType, name1, name2)` 缓存，命中即跳过 embedding 调用。

---

### 改进 ④：类型感知的评分权重（决策层）

**改动**：`AgentConfigProperties.MergeConfig` 扩展为 `Map<NodeType, ScoreWeights>`：

```yaml
legacygraph:
  agent:
    merge:
      scoreWeightsByType:
        BusinessDomain:   {name: 0.15, semantic: 0.45, struct: 0.10, evidence: 0.20, runtime: 0.05, history: 0.05}
        BusinessProcess:  {name: 0.20, semantic: 0.40, struct: 0.15, evidence: 0.15, runtime: 0.05, history: 0.05}
        BusinessObject:   {name: 0.30, semantic: 0.30, struct: 0.20, evidence: 0.15, runtime: 0.05, history: 0.00}
        Feature:          {name: 0.40, semantic: 0.20, struct: 0.20, evidence: 0.15, runtime: 0.05, history: 0.00}
        ApiEndpoint:      {name: 0.20, semantic: 0.05, struct: 0.40, evidence: 0.25, runtime: 0.10, history: 0.00}  # 现状
        Table:            {name: 0.30, semantic: 0.05, struct: 0.40, evidence: 0.20, runtime: 0.05, history: 0.00}
        default:          {name: 0.35, semantic: 0.00, struct: 0.25, evidence: 0.20, runtime: 0.10, history: 0.10}
```

**实现**：

```java
public ScoreWeights weightsFor(String nodeType) {
    return weightsByType.getOrDefault(nodeType, defaultWeights);
}
```

**效果**：同一服务、同一代码路径，按节点类型自动选择合适权重，不需要 LLM 区分。

---

### 改进 ⑤：跨类型候选桥接（业务域 ↔ 业务流程）

**问题**：业务域节点常与 `BusinessProcess`/`Feature`/`Page` 概念重叠（"用户中心" vs "用户管理流程" vs "用户列表页"）。

**改动**：新增跨类型候选生成通道（仅对业务域相关类型）：

```java
// 新增 CrossTypeBlockingService
public List<MergeCandidate> findCrossTypeCandidates(String projectId) {
    // 仅在 (BusinessDomain, BusinessProcess, Feature) 三类之间两两比较
    // 共享 evidenceIds > 2 且语义相似度 > 0.7 → 候选
    // 不直接 AUTO_MERGE，走 POSSIBLE_SAME_AS 边候选，由人工/LLM 决定
}
```

**输出**：通过 `EdgeType.POSSIBLE_SAME_AS` 边提示，由前端可视化让人工裁决，避免误并。

---

## 四、流水线级优化（运行层）

### 4.1 调度策略：扫描后置 + 增量触发

**当前**：合并只在被显式调用时执行，没有触发机制。

**新增 `GraphMergeScheduler`**：

```java
@Scheduled(cron = "0 0 3 * * ?")  // 每日 3:00 全量
public void dailyMerge() { ... }

@EventListener  // 扫描完成后
public void onScanCompleted(ScanCompletedEvent e) {
    // 仅跑"本版本新增节点"的合并，限 200 次 LLM 调用
}
```

### 4.2 LLM 调用降本

- 仅 0.50 < score < 0.85 的候选送 LLM（已实现）
- **新增**：`SemanticCache` 命中 → 直接用历史决策
- **新增**：批量请求 `llmGateway.callWithEnvelope` 一次合并多个候选（节省 50% 调用次数）

### 4.3 与 `GraphQualityAssessor` 联动

当前 `GraphQualityAssessor.assessAndReport` 已经会提示"重复节点 > 0，建议执行合并"。**新增**：当 `countDuplicateNodes > 5` 时自动触发一次增量合并。

---

## 五、前端配合

### 5.1 新增"合并候选视图"

`UnifiedGraph.vue` 已有 `pendingCount`，但没有合并候选对视图。

**新增组件**：`frontend/src/views/graph/MergeCandidatesPanel.vue`

- 订阅 `GET /api/graph/merge-candidates?projectId=...&nodeType=BusinessDomain`
- 双栏对比，左侧节点 A、右侧节点 B，中间显示 6 维评分雷达图
- 一键 "AUTO 合并" / "标记 REVIEW" / "拒绝"
- 合并后节点列表实时刷新

### 5.2 在图谱上高亮疑似重复节点

`UnifiedGraph.vue` 渲染时调用 `findMergeCandidates`，给相似节点加虚线框 + 同色高亮，hover 显示"疑似重复 N 个"。

---

## 六、实施路线（6 个 Phase）

| Phase | 周期 | 内容 | 验收 |
|---|---|---|---|
| **P1 数据层** | 1 周 | ① 激活 `aliasNames`；② 写 `BusinessDomainNormalizer`；③ `EvidenceGraphWriter` 透传别名 | 新节点都带 ≥2 个别名 |
| **P2 Blocking** | 1 周 | ④ `NodeBlockingService` 中文 bigram + 拼音通道；⑤ `findMergeCandidates` 走三通道 | 500 节点候选 < 25000（原 O(n²)=25 万） |
| **P3 语义评分** | 2 周 | ⑥ `SemanticSimilarityCalculator` + `SemanticCache` 接入；⑦ 权重按类型分桶 | 业务域节点合并召回 ≥ 70% |
| **P4 跨类型桥接** | 1 周 | ⑧ `CrossTypeBlockingService` + `POSSIBLE_SAME_AS` 边候选 | 跨类型候选可视化 |
| **P5 调度 + 联动** | 1 周 | ⑨ `GraphMergeScheduler`；⑩ 与 `GraphQualityAssessor` 联动 | 每日 3:00 自动合并；质量报告自动触发 |
| **P6 前端 + 评估** | 1 周 | ⑪ `MergeCandidatesPanel`；⑫ 高亮疑似重复；⑬ 评估脚本 | 人工抽样 100 对，误并 ≤ 5% |

---

## 七、风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| **Embedding 误并**：向量召回率高但精度低 | 大量误并 | 中间档 0.50-0.85 强制走 LLM；语义分权重不超 0.45 |
| **LLM 成本失控**：每次合并都调 LLM | 费用爆炸 | 仅中间档调 + SemanticCache 复用 + 批量请求 |
| **合并回滚复杂**：lineage 在 properties 里无索引 | 无法批量回滚 | P5 同步把 `mergedFrom` 拆为独立 `(:MergeLineage)` 节点 |
| **跨存储一致性**：Neo4j 已合并但 PG 证据未迁移 | 数据漂移 | P5 用 `ChainedTransactionManager` 补强 |
| **Blocking 召回不足**：拼音首字母冲突 | 漏合并 | 候选上限 Top-50 兜底 + 50 邻居滑动（已有） |

---

## 八、关键文件改动清单

**新增（5 个）**：

- `backend/src/main/java/io/github/legacygraph/service/graph/NodeBlockingService.java`
- `backend/src/main/java/io/github/legacygraph/service/graph/CrossTypeBlockingService.java`
- `backend/src/main/java/io/github/legacygraph/service/similarity/SemanticSimilarityCalculator.java`
- `backend/src/main/java/io/github/legacygraph/service/normalize/BusinessDomainNormalizer.java`
- `backend/src/main/java/io/github/legacygraph/service/scan/GraphMergeScheduler.java`

**修改（6 个）**：

- `backend/.../service/graph/GraphMergeService.java` — 接入三通道 Blocking + 语义分
- `backend/.../builder/EvidenceGraphWriter.java` — 透传 `aliasNames`
- `backend/.../builder/GraphBuilder.java` — 业务域走 `BusinessDomainNormalizer`
- `backend/.../config/AgentConfigProperties.java` — `scoreWeightsByType` 分桶配置
- `backend/.../service/scan/GraphQualityAssessor.java` — 重复节点 > 5 自动触发合并
- `frontend/src/views/graph/UnifiedGraph.vue` — 合并候选高亮 + 面板入口

**新增前端组件**：

- `frontend/src/views/graph/MergeCandidatesPanel.vue`
- `frontend/src/api/merge.api.ts`

---

## 九、评估指标

| 指标 | 当前基线 | 目标 |
|---|---|---|
| 业务域合并召回率 | < 30% | ≥ 85% |
| AUTO_MERGE 误并率 | — | ≤ 5%（人工抽样 100 对） |
| 单 project 全量合并 LLM 调用次数 | — | ≤ 200 |
| 全量合并耗时（500 节点） | — | ≤ 60s |
| 重复节点数（GraphQualityAssessor） | > 5 | < 2 |

---

## 十、参考代码锚点

| 关注点 | 行号 | 文件 |
|---|---|---|
| Blocking 实现 | 85-91, 109-122 | `backend/.../service/graph/GraphMergeService.java` |
| 5 维评分 | 154-161 | 同上 |
| 三档决策 | 273-287 | 同上 |
| 合并执行 | 300-325 | 同上 |
| 归一化（中英后缀剥离） | 368-373 | 同上 |
| 配置类 | 21-67 | `backend/.../config/AgentConfigProperties.java` |
| LLM 决策入口（参数位） | `decideMerge(...)` | `backend/.../agent/GraphMergeAgent.java` |
| 跨语言合并支撑 | 295-298 | `backend/.../dao/Neo4jGraphDao.java` |
| 跨存储一致性风险 | 注释 B-S1 | `backend/.../builder/EvidenceGraphWriter.java` |
| 重复节点统计 | `countDuplicateNodes` | `backend/.../dao/Neo4jGraphDao.java` |
