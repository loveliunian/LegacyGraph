# Graphify JSON 契约规范

> 版本：v0.9  
> 最后更新：2026-07-06

## 概述

本文档定义 Graphify 输出的 `graph.json` 文件格式规范，用于与 LegacyGraph 系统集成。

## Schema 版本

| 版本 | 标识 | 说明 |
|------|------|------|
| v0.9 | `V0_9_NETWORKX_LINKS` | 使用 `links` 字段（NetworkX 格式） |
| v0.9 | `V0_9_NETWORKX_EDGES` | 使用 `edges` 字段（替代格式） |

## 顶层字段

### 必填字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `nodes` | `Node[]` | 节点列表，不能为空 |
| `links` 或 `edges` | `Edge[]` | 边列表，至少一个存在 |

### 可选字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `directed` | `boolean` | 是否有向图 |
| `hyperedges` | `Hyperedge[]` | 超边列表（用于多节点关系） |
| `built_at_commit` | `string` | 构建时的 Git commit hash |

## 节点结构 (Node)

```json
{
  "id": "string",
  "label": "string",
  "file_type": "string",
  "source_file": "string",
  "source_location": "string",
  "community": "integer",
  "community_name": "string",
  "norm_label": "string"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | `string` | ✅ | 节点唯一标识 |
| `label` | `string` | ✅ | 节点标签/名称 |
| `file_type` | `string` | ❌ | 文件类型：`code`, `sql`, `vue`, `jsx`, `tsx` 等 |
| `source_file` | `string` | ❌ | 源文件路径（使用 `/` 分隔） |
| `source_location` | `string` | ❌ | 源码位置（如行号） |
| `community` | `integer` | ❌ | 社区编号 |
| `community_name` | `string` | ❌ | 社区名称 |
| `norm_label` | `string` | ❌ | 归一化标签 |

## 边结构 (Edge / Link)

```json
{
  "source": "string",
  "target": "string",
  "relation": "string",
  "confidence": "string",
  "confidence_score": "number",
  "source_file": "string",
  "source_location": "string"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `source` | `string` | ✅ | 源节点 ID |
| `target` | `string` | ✅ | 目标节点 ID |
| `relation` | `string` | ✅ | 关系类型 |
| `confidence` | `string` | ❌ | 置信度等级：`EXTRACTED`, `INFERRED`, `AMBIGUOUS` |
| `confidence_score` | `number` | ❌ | 置信度分数（0.0-1.0） |
| `source_file` | `string` | ❌ | 源文件路径 |
| `source_location` | `string` | ❌ | 源码位置 |

### 关系类型映射

| Graphify relation | LegacyGraph EdgeType |
|-------------------|---------------------|
| `calls`, `invokes` | `CALLS` |
| `extends`, `inherits`, `implements` | `IMPLEMENTED_BY` |
| `imports` | `USES` |
| `uses`, `depends_on` | `USES` |
| `reads_from`, `reads` | `READS` |
| `writes_to`, `writes` | `WRITES` |
| `contains`, `has` | `CONTAINS` |
| 其他 | `USES` |

### 置信度映射

| Graphify confidence | 分数 | 状态 |
|---------------------|------|------|
| `EXTRACTED` | 0.95 | `CONFIRMED` |
| `INFERRED` | `confidence_score` 或 0.75 | `PENDING_CONFIRM` |
| `AMBIGUOUS` | 0.45 | `PENDING_CONFIRM` |
| 未指定 | 0.95 | `CONFIRMED` |

## 兼容性检查

LegacyGraph 在导入前会执行兼容性检查，验证：

1. **必填字段存在**：`nodes` 非空，`links` 或 `edges` 至少一个存在
2. **未知字段警告**：记录不在规范中的顶层字段
3. **Schema 版本识别**：根据 `links`/`edges` 判断版本

### 检查失败场景

| 场景 | 错误信息 |
|------|----------|
| `nodes` 缺失或为空 | `兼容性检查失败: 缺失字段=[nodes]` |
| `links` 和 `edges` 都缺失 | `兼容性检查失败: 缺失字段=[links 或 edges]` |
| JSON 解析失败 | `兼容性检查失败: 缺失字段=[JSON 解析失败: ...]` |

## 示例

### 完整示例

```json
{
  "directed": true,
  "built_at_commit": "abc123def",
  "nodes": [
    {
      "id": "UserController",
      "label": "UserController",
      "file_type": "code",
      "source_file": "src/main/java/UserController.java",
      "source_location": "L10-L50",
      "community": 1,
      "community_name": "用户模块"
    },
    {
      "id": "UserService",
      "label": "UserService",
      "file_type": "code",
      "source_file": "src/main/java/UserService.java"
    }
  ],
  "links": [
    {
      "source": "UserController",
      "target": "UserService",
      "relation": "calls",
      "confidence": "EXTRACTED",
      "confidence_score": 0.98,
      "source_file": "src/main/java/UserController.java",
      "source_location": "L25"
    }
  ]
}
```

## 文件约束

| 约束 | 值 |
|------|-----|
| 文件名 | `graph.json` |
| 最大文件大小 | 50 MB |
| 路径分隔符 | `/`（Unix 格式） |

## 节点类型推断

LegacyGraph 根据 `file_type` 和 `label` 推断节点类型：

| file_type | label 特征 | NodeType |
|-----------|-----------|----------|
| `code` | 含 `Controller` | `Controller` |
| `code` | 含 `Service` | `Service` |
| `code` | 含 `Repository`/`Mapper` | `Mapper` |
| `code` | 含 `Entity`/`Model` | `BusinessObject` |
| `code` | 含 `Config` | `ConfigItem` |
| `code` | 其他 | `Method` |
| `sql` | - | `Table` |
| `vue`/`jsx`/`tsx` | - | `Page` |
| 其他 | - | `Feature` |
