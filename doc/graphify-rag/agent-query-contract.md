# Agent 查询契约文档

## 概述

本文档定义 Graphify 与 Agent/RAG 系统之间的查询接口契约，确保查询结果的可追溯性和权限控制。

## 请求格式

### GraphifyQuestionRequest

```java
record GraphifyQuestionRequest(
    String question,           // 必填：查询问题
    String projectId,          // 必填：项目 ID
    Set<String> callerRoles,   // 必填：调用方角色集合
    int maxEvidence            // 可选：最大证据数量（默认 10）
)
```

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| `question` | String | ✅ | 自然语言查询问题 |
| `projectId` | String | ✅ | 目标项目 ID |
| `callerRoles` | Set<String> | ✅ | 调用方角色集合，用于权限控制 |
| `maxEvidence` | int | ❌ | 返回的最大证据数量，默认 10 |

### 支持的角色

- `GRAPHIFY_ADMIN`: 系统管理员，可查看所有证据
- `GRAPH_REVIEWER`: 图谱审核员，可查看脱敏证据
- `GRAPH_EVIDENCE_VIEWER`: 证据查看者，可查看原始证据

## 响应格式

### GraphifyQuestionAnswer

```java
record GraphifyQuestionAnswer(
    String answer,              // 查询结果
    Set<String> evidenceIds,    // 证据 ID 集合
    List<String> sourcePaths,   // 源码路径（已脱敏）
    double confidence,          // 置信度 [0.0, 1.0]
    List<String> warnings       // 警告信息
)
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `answer` | String | 自然语言回答 |
| `evidenceIds` | Set<String> | 支持的证据 ID 列表，可追溯到原始 graph.json |
| `sourcePaths` | List<String> | 相关源码文件路径（根据权限脱敏） |
| `confidence` | double | 答案置信度，0.0 到 1.0 之间 |
| `warnings` | List<String> | 查询过程中的警告信息 |

## 权限控制

### 权限矩阵

| 角色 | 查看图谱 | 查看原始证据 | 查看脱敏证据 |
|------|:---:|:---:|:---:|
| GRAPHIFY_ADMIN | ✅ | ✅ | ✅ |
| GRAPH_REVIEWER | ✅ | ❌ | ✅ |
| GRAPH_EVIDENCE_VIEWER | ✅ | ✅ | ✅ |

### 权限检查流程

```
1. 检查调用方角色
2. 验证 GRAPH_VIEWER 权限
3. 根据角色决定是否脱敏源码路径
4. 返回结果
```

## 置信度说明

### 置信度计算

- **1.0**: 完全匹配，多个高质量证据支持
- **0.8-0.9**: 高置信度，有明确证据支持
- **0.5-0.7**: 中等置信度，证据有限或部分推断
- **0.3-0.4**: 低置信度，主要基于推断
- **0.0-0.2**: 极低置信度，无可靠证据

### 置信度影响因素

1. **证据数量**: 更多证据 → 更高置信度
2. **证据类型**: confirmed 边 > inferred 边
3. **路径长度**: 短路径 > 长路径
4. **节点度**: 适度连接的节点 > 孤立或过度连接的节点

## 证据追溯

每个 `evidenceId` 可追溯到：

1. **原始 graph.json**: 具体的节点/边记录
2. **KnowledgeClaim**: 结构化知识声明
3. **审核记录**: 审核员的操作历史
4. **导入作业**: 产生该证据的 Graphify 导入作业

## 错误处理

### 常见错误

| 错误 | 原因 | 处理方式 |
|------|------|---------|
| Access denied | 权限不足 | 返回 0.0 置信度，附带权限要求 |
| Empty question | 空问题 | 返回空结果，附带提示 |
| Query failed | 内部错误 | 返回错误信息，附带异常类型 |

## 使用示例

```bash
curl -X POST http://localhost:8080/api/lg/projects/proj-123/graphify/questions \
  -H "Content-Type: application/json" \
  -d '{
    "question": "UserService 依赖哪些数据库表？",
    "callerRoles": ["GRAPH_REVIEWER"],
    "maxEvidence": 5
  }'
```

响应：
```json
{
  "answer": "UserService 依赖 lg_user 和 lg_user_role 表",
  "evidenceIds": ["evidence-001", "evidence-002"],
  "sourcePaths": ["src/main/java/service/UserService.java"],
  "confidence": 0.92,
  "warnings": []
}
```
