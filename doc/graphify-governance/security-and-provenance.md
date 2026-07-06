# Graphify 安全与溯源文档

## 概述

本文档描述 Graphify 集成中的安全控制机制，包括 RBAC 权限模型和源码路径脱敏规则。

## RBAC 角色模型

### 角色定义

| 角色 | 说明 | 权限范围 |
|------|------|---------|
| `GRAPHIFY_ADMIN` | 系统管理员 | 全部操作权限，包括运行导入、管理规则、查看审计 |
| `GRAPH_REVIEWER` | 图谱审核员 | 审核边、查看审核队列、查看脱敏证据 |
| `GRAPH_EVIDENCE_VIEWER` | 证据查看者 | 查看原始证据和源码路径 |

### 权限矩阵

| 操作 | GRAPHIFY_ADMIN | GRAPH_REVIEWER | GRAPH_EVIDENCE_VIEWER |
|------|:-:|:-:|:-:|
| 运行导入 (`run import`) | ✅ | ❌ | ❌ |
| 审核边 (`review edges`) | ✅ | ✅ | ❌ |
| 查看原始证据 (`view raw evidence`) | ✅ | ❌ | ✅ |
| 查看脱敏证据 | ✅ | ✅ | ✅ |
| 管理规则 | ✅ | ❌ | ❌ |
| 查看审计日志 | ✅ | ❌ | ❌ |

### 权限继承

`GRAPHIFY_ADMIN` 是最高权限角色，包含其他角色的所有权限。

## 路径脱敏规则

### GraphifyProvenanceRedactor

路径脱敏器确保：

1. **项目内路径** → 转换为相对路径
   - 输入: `/Users/dev/project/src/main/java/Service.java`
   - 输出: `src/main/java/Service.java`

2. **项目外路径** → 返回 `[outside-project]`
   - 输入: `/Users/dev/other-project/Config.java`
   - 输出: `[outside-project]`

3. **空路径** → 返回 `[unknown]`

### 脱敏流程

```
原始路径 → 判断是否在项目根目录下
  ↓ 是                    ↓ 否
转为相对路径          [outside-project]
```

## 原始证据保护

- 原始 `graph.json` 文件存储为不可变证据
- 审核操作不会修改原始文件
- 所有结构化边通过 `KnowledgeClaim` 关联到原始证据
- 支持从结构化边追溯到原始 `graph.json` 中的具体记录

## 安全审计

所有安全相关操作均记录到审计日志：

- 导入操作（谁在什么时候导入了什么）
- 审核操作（谁审核了哪些边，结果如何）
- 权限变更（角色分配变更历史）
- 证据访问（谁查看了哪些原始证据）
