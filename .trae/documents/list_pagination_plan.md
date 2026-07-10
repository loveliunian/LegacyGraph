# 列表接口分页改造计划

## 1. 需求分析

### 1.1 问题背景
项目中部分列表接口未实现分页，当数据量大时会导致：
- 前端渲染性能问题
- 网络传输压力大
- 后端内存占用高

### 1.2 待改造接口清单

| 序号 | Controller | 接口方法 | 路径 | 返回类型 | 优先级 |
|:---:|-----------|---------|------|---------|:-----:|
| 1 | ReportController | listReports | `/lg/projects/{projectId}/reports/list` | `List<Report>` | 高 |
| 2 | ChangeTaskController | list | `/change-tasks?projectId=xxx` | `List<ChangeTask>` | 高 |
| 3 | ScanController | getLogs | `/lg/projects/{projectId}/scan/{versionId}/logs` | `List<Map<String, Object>>` | 高 |
| 4 | GraphifyJobController | listJobs | `/lg/projects/{projectId}/graphify/jobs` | `List<GraphifyImportJob>` | 中 |
| 5 | EnhancedQaController | listConversations | `/qa/conversations?projectId=xxx` | `List<QaConversation>` | 中 |
| 6 | EnhancedQaController | getMessages | `/qa/conversations/{id}/messages` | `List<QaMessage>` | 中 |
| 7 | EvidenceConflictController | list | `/lg/evidence-conflicts?projectId=xxx` | `List<EvidenceConflict>` | 中 |

### 1.3 已有分页模式参考

项目已统一使用 `PageQuery` 入参和 `PageResult` 返回值模式：

```java
// 入参
@Data
public class PageQuery {
    private Integer pageNum = 1;
    private Integer pageSize = 20;
    private String keyword;
}

// 返回值
@Data
public class PageResult<T> {
    private List<T> list;
    private Long total;
    private Integer pageNum;
    private Integer pageSize;
    private Integer totalPages;
}
```

## 2. 改造方案

### 2.1 通用改造模式

#### 2.1.1 Controller 层改造
```java
// 修改前
public Result<List<Entity>> list(@RequestParam String projectId) {
    return Result.success(service.list(projectId));
}

// 修改后
public Result<PageResult<Entity>> list(
        @RequestParam String projectId,
        PageQuery query) {
    Page<Entity> page = repository.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            wrapper);
    return Result.success(PageResult.of(page.getRecords(), page.getTotal(), 
            query.getPageNum(), query.getPageSize()));
}
```

#### 2.1.2 Service 层改造
对于需要保留旧逻辑的场景，新增分页方法：
```java
public PageResult<Entity> listPage(String projectId, int pageNum, int pageSize) {
    Page<Entity> page = repository.selectPage(
            new Page<>(pageNum, pageSize),
            wrapper);
    return PageResult.of(page.getRecords(), page.getTotal(), pageNum, pageSize);
}
```

### 2.2 各接口详细改造方案

#### 2.2.1 ReportController.listReports

**文件**: `backend/src/main/java/io/github/legacygraph/controller/ReportController.java`

**改造内容**:
- 修改返回类型为 `PageResult<Report>`
- 新增 `PageQuery` 参数
- 使用 `reportRepository.selectPage` 实现分页

**Service 层**: `ReportingService.listReports` 需要新增分页版本

#### 2.2.2 ChangeTaskController.list

**文件**: `backend/src/main/java/io/github/legacygraph/controller/ChangeTaskController.java`

**改造内容**:
- 修改返回类型为 `PageResult<ChangeTask>`
- 新增 `PageQuery` 参数
- 使用 MyBatis-Plus 的 `Page` 对象实现分页

**Service 层**: `ChangeTaskService.listTasks` 需要新增分页版本

#### 2.2.3 ScanController.getLogs

**文件**: `backend/src/main/java/io/github/legacygraph/controller/ScanController.java`

**改造内容**:
- 修改返回类型为 `PageResult<Map<String, Object>>`
- 新增 `PageQuery` 参数
- 在 `ScanVersionService.getScanLogs` 中实现分页

#### 2.2.4 GraphifyJobController.listJobs

**文件**: `backend/src/main/java/io/github/legacygraph/controller/GraphifyJobController.java`

**改造内容**:
- 修改返回类型为 `PageResult<GraphifyImportJob>`
- 新增 `PageQuery` 参数
- 在 `GraphifyImportJobService` 中新增分页方法

**注意**: 该服务使用 Spring Data JPA，需要使用 `Pageable` 实现分页

#### 2.2.5 EnhancedQaController.listConversations

**文件**: `backend/src/main/java/io/github/legacygraph/controller/EnhancedQaController.java`

**改造内容**:
- 修改返回类型为 `PageResult<QaConversation>`
- 新增 `PageQuery` 参数
- 在 `ConversationContextManager.listConversations` 中实现分页

#### 2.2.6 EnhancedQaController.getMessages

**文件**: `backend/src/main/java/io/github/legacygraph/controller/EnhancedQaController.java`

**改造内容**:
- 修改返回类型为 `PageResult<QaMessage>`
- 新增 `PageQuery` 参数
- 在 `ConversationContextManager` 中新增分页查询消息方法

#### 2.2.7 EvidenceConflictController.list

**文件**: `backend/src/main/java/io/github/legacygraph/controller/EvidenceConflictController.java`

**改造内容**:
- 修改返回类型为 `PageResult<EvidenceConflict>`
- 新增 `PageQuery` 参数
- 在 `EvidenceConflictService.list` 中实现分页

### 2.3 前端改造

#### 2.3.1 API 层改造
需要同步修改前端 API 调用：

**文件**: `frontend/src/api/report.api.ts`
- 修改 `listReports` 返回类型为分页结构

#### 2.3.2 组件层改造
**文件**: `frontend/src/views/workbench/SystemOverviewWorkbench.vue`
- 修改报告列表获取逻辑，支持分页

## 3. 实施步骤

### Phase 1: 后端改造（7个接口）

| 步骤 | 接口 | 预计改动文件数 |
|:---:|------|:------------:|
| 1 | ReportController.listReports | 2 (Controller + Service) |
| 2 | ChangeTaskController.list | 2 (Controller + Service) |
| 3 | ScanController.getLogs | 2 (Controller + Service) |
| 4 | GraphifyJobController.listJobs | 2 (Controller + Service) |
| 5 | EnhancedQaController.listConversations | 2 (Controller + Service) |
| 6 | EnhancedQaController.getMessages | 2 (Controller + Service) |
| 7 | EvidenceConflictController.list | 2 (Controller + Service) |

### Phase 2: 前端改造

| 步骤 | 文件 | 说明 |
|:---:|------|------|
| 8 | `report.api.ts` | 修改返回类型 |
| 9 | `SystemOverviewWorkbench.vue` | 支持分页查询 |

## 4. 风险与依赖

### 4.1 风险点

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 接口返回类型变更 | 前端调用失败 | 同步更新前端 API 定义 |
| 分页参数遗漏 | 部分接口仍返回全量 | 严格按照改造方案实施 |
| JPA vs MyBatis-Plus | Graphify 使用 JPA | 使用 `Pageable` 实现 |

### 4.2 依赖检查

- [x] `PageQuery` - 已存在
- [x] `PageResult` - 已存在
- [x] MyBatis-Plus `Page` - 已集成
- [x] Spring Data JPA `Pageable` - 已集成

## 5. 验证方案

### 5.1 单元测试
- 每个改造的 Service 方法新增分页测试用例

### 5.2 接口测试
- 使用 Swagger 验证分页参数生效
- 验证 `total`、`pageNum`、`pageSize`、`totalPages` 字段正确

### 5.3 前端验证
- 验证报告列表分页功能正常

## 6. 改造影响范围

### 6.1 向后兼容性
- 接口返回结构变更，前端需要同步更新
- 建议前端配合后端一起发布

### 6.2 数据库影响
- 新增分页查询，使用 `LIMIT/OFFSET`，性能优于全量查询
