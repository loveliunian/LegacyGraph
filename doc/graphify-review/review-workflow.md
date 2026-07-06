# Graphify 审核工作流文档

## 概述

Graphify 审核工作流确保 AI 生成的图谱边经过人工审核后才能进入结构化知识库，保障图谱质量。

## 审核规则引擎

### 规则类型

| 规则名称 | 说明 | 触发条件 |
|---------|------|---------|
| `LowConfidenceRule` | 低置信度边需审核 | `confidence < 0.7` |
| `InferredEdgeRule` | 推断边需审核 | `sourceType = GRAPHIFY_SEMANTIC` |
| `CrossPackageRule` | 跨包依赖需审核 | 源节点和目标节点在不同包 |
| `HighFanInRule` | 高扇入节点需审核 | 入度 > 20 |

### 规则评估流程

```
边输入 → 规则链评估 → 产生 ReviewDecision
  ↓
PASS → 自动通过
FLAG → 加入审核队列
REJECT → 自动拒绝（记录原因）
```

### ReviewDecision 结构

```java
record ReviewDecision(
    ReviewStatus status,      // PASS / FLAG / REJECT
    List<String> reasons,     // 触发原因列表
    String ruleName,          // 触发规则名
    double confidence         // 原始置信度
)
```

## 批量操作

### 批量审核接口

- `POST /api/lg/projects/{projectId}/graphify/review/batch` - 批量审核边
- `GET /api/lg/projects/{projectId}/graphify/review/queue` - 获取审核队列

### 支持的批量操作

- **批量通过**: 将多条边标记为 `confirmed`
- **批量拒绝**: 将多条边标记为 `rejected`
- **按规则批量处理**: 按规则名称批量操作

## 审计回放

所有审核操作均记录到审计日志，支持：

- 按时间范围查询审核历史
- 按操作人查询审核记录
- 按规则名称查询触发记录
- 审核结果导出

## 规则沉淀

审核过程中发现的新模式可以沉淀为新规则：

1. 审核员发现新的风险模式
2. 提交规则建议
3. 规则引擎管理员评审
4. 新规则上线生效

## 角色权限

| 角色 | 权限 |
|------|------|
| `GRAPH_REVIEWER` | 审核边、查看审核队列 |
| `GRAPHIFY_ADMIN` | 管理规则、查看审计日志 |
| `GRAPH_EVIDENCE_VIEWER` | 查看原始证据 |
