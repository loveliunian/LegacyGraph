# LegacyGraph 前端 API 调用模式审计报告

**审计时间**: 2026-07-04  
**审计范围**: frontend/src/views/, frontend/src/stores/, frontend/src/api/

---

## 问题汇总

| 类别 | 严重程度 | 数量 |
|------|---------|------|
| await 调用缺少 try-catch | 🔴 高 | 20 |
| Store 与组件重复调用 API | 🟡 中 | 3 |
| await 返回值未使用（潜在问题） | 🟢 低 | 15-20 |

---

## 🔴 高严重度：await 调用缺少异常处理

以下 `await` 调用没有 `try-catch` 包裹，API 失败时会导致未捕获异常：

### graph/ 目录（13处）

1. **BusinessGraph.vue**
   - Line 199: `const result = await loadScanVersions(pid)` - 加载扫描版本
   - Line 375: `await loadVersions()` - onMounted 中加载版本
   - Line 385: `await loadGraph(domainQuery || '')` - 加载图谱数据

2. **DataLineageGraph.vue**
   - Line 344: `versions.value = await loadScanVersions(pid)` - 加载版本
   - Line 569, 577: `await loadLineageData(...)` - 加载血缘数据（2处）
   - Line 580, 594: `await loadTablesFromBackend()` - 加载表数据（2处）
   - Line 585: `await loadVersions()` - onMounted 中加载版本

3. **RuntimeGraph.vue**
   - Line 475, 496: `await loadTraces()` - 加载调用链数据（2处）

4. **UnifiedGraph.vue**
   - Line 551: `const result = await loadScanVersions(pid)` - 加载版本
   - Line 603, 720, 723: `await loadGraph(...)` - 加载图谱（3处）

5. **FeatureGraph.vue**
   - Line 219: `const result = await loadScanVersions(pid)` - 加载版本
   - Line 408, 422: `await testApi.generate(...)` - 生成测试用例（2处）

6. **CodeGraph.vue**
   - Line 162: `const result = await loadScanVersions(pid)` - 加载版本
   - Line 176: `await queryGraph()` - 查询图谱
   - Line 291: `await loadVersions()` - onMounted 中加载版本

### change/ 目录（2处）

7. **ChangeTaskList.vue**
   - Line 362: `await changeTaskApi.refreshImpact(row.id, value)` - 刷新影响分析
   - Line 375: `await changeTaskApi.generatePatch(row.id, {})` - 生成补丁
   - Line 388: `await changeTaskApi.runValidation(...)` - 运行验证

### source/ 目录（3处）

8. **CodeRepoList.vue**
   - Line 416: `await sourceApi.pullRepo(projectId, row.id)` - 拉取代码仓库

9. **DatabaseList.vue**
   - Line 326: `await sourceApi.createDbConnection(...)` - 创建数据库连接

10. **DocumentList.vue**
    - Line 437: `await ElMessageBox.confirm(...)` - 删除确认（虽然不会失败，但建议统一处理）

### test/ 目录（2处）

11. **TestRunDetail.vue**
    - Line 174: `await testRunApi.rerunFailed(projectId, runId)` - 重跑失败用例

12. **TestCaseList.vue**
    - Line 413: `await testApi.run(projectId!, row.id, 'test')` - 运行测试
    - Line 432: `await testApi.delete(projectId!, row.id)` - 删除用例
    - Line 471: `await testApi.generate(projectId!, {...})` - 生成测试用例

13. **TestCaseEditor.vue**
    - Line 177: `await testApi.update(projectId, caseId, formData.value)` - 更新用例
    - Line 180: `await testApi.create(projectId, formData.value)` - 创建用例

---

## 🟡 中严重度：Store 与组件重复调用 API

以下组件直接调用 API，但对应的 Store 中已有相同的 API 调用逻辑，造成代码重复和状态不一致风险：

### 1. ProjectList.vue 绕过 projectStore

**问题位置**: `views/project/ProjectList.vue`

**重复调用**:
- Line 165: `await projectApi.list(query)` → 应使用 `projectStore.fetchProjectList()`
- Line 184: `await projectApi.create(newProject)` → 应使用 `projectStore.createProject()`
- Line 215: `await projectApi.delete(id)` → 应使用 `projectStore.deleteProject()`

**影响**: 
- Store 中的 `projectList` 状态与组件本地状态不同步
- 其他依赖 `projectStore.projectList` 的组件无法感知变更

**建议修复**:
```typescript
// 替换直接 API 调用
const projectStore = useProjectStore()

// Line 165
const data = await projectStore.fetchProjectList({ keyword: query.keyword })

// Line 184
await projectStore.createProject(newProject)

// Line 215
await projectStore.deleteProject(id)
```

### 2. Dashboard/Index.vue 重复查询项目详情

**问题位置**: `views/dashboard/Index.vue`

**重复调用**:
- Line 617: `projectApi.detail(pid)` → Store 已有 `fetchCurrentProject()`

**建议**: 使用 `projectStore.fetchCurrentProject()` 或直接读取 `projectStore.currentProject`

### 3. ScanVersionList.vue 重复轮询任务进度

**问题位置**: `views/scan/ScanVersionList.vue`

**重复调用**:
- Line 488: `await scanApi.progress(projectId, currentVersion.value.id)` 
- Store `task.ts` 中已有 `startPolling()` 机制

**影响**: 
- 组件和 Store 各自维护轮询逻辑，可能导致重复请求
- 任务状态分散在两处，难以统一管理

**建议**: 统一使用 `useTaskStore().startPolling(taskId, projectId)` 管理轮询

---

## 🟢 低严重度：await 返回值未使用

以下调用虽然"未使用返回值"，但**大多是故意的设计**（仅使用副作用），列出供确认：

### 合理的使用（无需修改）

- `await loadList()` / `await loadVersions()` - 刷新列表（函数内部已更新 ref）
- `await ElMessageBox.confirm()` - 仅使用确认/取消的副作用
- `await someApi.delete/update/create()` - 仅使用写操作的副作用
- `await formRef.value.validate()` - 仅使用验证的副作用

### 可能的问题（建议检查）

1. **GraphQa.vue**
   - Line 269: `await scrollToBottom()` - 确认是否需要错误处理
   - Line 349: `await loadConversationMessages(convId)` - 确认返回值是否需要处理
   - Line 366: `await qaApi.deleteConversation(convId)` - 删除后应刷新列表

2. **PrWorkbench.vue**
   - Line 250: `await agentApi.prDescribe({...})` - 确认是否需要处理返回结果

3. **ScanVersionList.vue**
   - Line 526: `await scanApi.cancel(...)` - 取消后应刷新列表
   - Line 541: `await scanApi.pause(...)` - 暂停后应刷新列表
   - Line 556: `await scanApi.resume(...)` - 恢复后应刷新列表
   - Line 571: `await scanApi.delete(...)` - 删除后应刷新列表

---

## ✅ 已验证无问题的区域

### Store Action 状态更新

所有 Store 的 action 在调用 API 后都正确更新了 state：

- ✅ `projectStore.fetchProjectList()` → 更新 `projectList.value`
- ✅ `projectStore.fetchCurrentProject()` → 更新 `currentProject.value`
- ✅ `projectStore.createProject()` → 更新 `projectList.value`
- ✅ `projectStore.deleteProject()` → 更新 `projectList.value` 和 `currentProject`
- ✅ `userStore.login()` → 更新 `accessToken`, `refreshToken`, `userInfo`
- ✅ `userStore.logout()` → 调用 `clearAuth()` 清空状态
- ✅ `userStore.fetchCurrentUser()` → 更新 `userInfo`
- ✅ `taskStore.startPolling()` → 通过 `updateTaskProgress()` 更新任务进度

### Import 检查

所有从 `@/api/*` 导入的函数和类型都在对应的 API 文件中正确导出，无缺失问题。

### onMounted 前置条件检查

所有 onMounted 中的 API 调用都有适当的前置条件检查：

- `loadVersions()` 内部检查 `if (!pid) return`
- `loadTablesFromBackend()` 内部检查 `if (!projectId.value || !currentVersion.value) return`
- 其他加载函数都有类似的空值保护

---

## 修复建议优先级

### 🔴 立即修复（高优先级）

1. **为所有 graph/ 组件的 await 调用添加 try-catch**
   - 尤其是 `loadScanVersions()`, `loadGraph()`, `loadLineageData()` 等核心 API
   - 添加 `loading` 状态管理和错误提示

2. **为 change/ 和 test/ 组件的写操作添加错误处理**
   - `changeTaskApi.refreshImpact()`, `generatePatch()`, `runValidation()`
   - `testApi.run()`, `delete()`, `generate()`

### 🟡 近期优化（中优先级）

1. **统一 ProjectList.vue 使用 projectStore**
   - 消除直接 API 调用，通过 Store 管理项目列表状态

2. **统一 ScanVersionList.vue 使用 taskStore 的轮询机制**
   - 避免组件和 Store 重复维护轮询逻辑

### 🟢 后续改进（低优先级）

1. **为所有 delete/update/create 操作添加统一的错误处理**
   - 使用全局错误拦截器或统一的 API wrapper

2. **添加 TypeScript 严格模式检查**
   - 捕获未处理的 Promise rejection

---

## 统计信息

- **扫描文件数**: 50+ Vue 组件，5 个 Store，20 个 API 模块
- **总 await 调用数**: ~200+
- **问题检出率**: ~12%（24个问题 / 200+ await 调用）
- **高严重度问题**: 20 个（主要在 graph/ 和 test/ 模块）
- **中严重度问题**: 3 个（Store 重复调用）
- **低严重度问题**: 15-20 个（返回值未使用，大多合理）

---

**审计完成** ✨
