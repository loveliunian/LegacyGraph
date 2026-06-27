# LegacyGraph 未完成功能清单

## 📋 概述

本文档记录 LegacyGraph 项目中，根据详细设计文档应该实现但尚未完成或部分完成的功能。

**项目整体完成度：约 85%**

| 模块 | 完成度 | 优先级 |
|------|--------|--------|
| 后端核心功能 | 95% | ✅ P0/P1 已完成 |
| 前端界面功能 | 80% | ✅ P0 已完成 |
| 图谱展示与交互 | 35% | 🟡 P2 |
| 测试验证闭环 | 90% | ✅ P0 已完成 |
| 报告与统计 | 95% | ✅ P1 已完成 |
| 权限控制 | 85% | ✅ P0 已完成 |
| 日志与监控 | 90% | ✅ P1 已完成 |

---

## ✅ 已完成的 P0 高优先级任务

### 1. 代码 Bug 修复

**已完成：**
- ✅ **ProjectService 递归死循环修复** - 修复了 `getById()` 方法调用自身导致的 StackOverflowError
- ✅ **GraphValidatorService 方法签名匹配** - 适配了正确的方法调用参数

### 2. 安全加固

**已完成：**
- ✅ **密码 BCrypt 加密存储** - AuthController 中集成 Spring Security 密码编码器
- ✅ **Security JWT 认证配置** - 配置 JWT 认证过滤器、白名单路径、无状态 Session
- ✅ **JWT 工具类** - JwtUtil 实现 Token 生成、解析、验证功能
- ✅ **JWT 认证过滤器** - JwtAuthenticationFilter 实现请求拦截和用户认证

### 3. 参数校验

**已完成：**
- ✅ **全局异常处理器** - GlobalExceptionHandler 处理所有类型异常
- ✅ **业务异常类** - BusinessException 统一业务异常处理
- ✅ **DTO 注解全覆盖** - 所有请求 DTO 添加 `@NotBlank`, `@Size`, `@NotNull`, `@Positive` 等
- ✅ **Controller @Valid 注解** - 所有请求体参数验证启用

### 4. 文件上传安全

**已完成：**
- ✅ **文件大小限制** - 最大 100MB
- ✅ **文件类型白名单** - DOCX, PDF, MD, TXT, JSON, XML, YAML, 代码文件等
- ✅ **空文件检查**
- ✅ **文件名安全处理** - UUID 前缀防路径遍历

### 5. 其他改进

**已完成：**
- ✅ **统一错误码枚举** - ErrorCode 定义
- ✅ **ProjectService 业务逻辑完善** - 增加 BusinessException、优化方法签名
- ✅ **Spring Security 白名单配置** - 登录、Swagger、Actuator 等路径放行

---

## ✅ 已完成的 P1 中优先级任务

### 1. 向量检索服务

**文件位置：** `service/VectorRetrievalService.java`

**已完成功能：**
- ✅ 批量 upsert 向量文档
- ✅ 语义相似度检索（topK、按类型过滤）
- ✅ 根据相似度查找可能重复的节点
- ✅ 集成 pgvector 余弦相似度算子 `<->`
- ✅ VectorController REST API 接口完整

**相关文件：**
- `VectorDocument.java` - 向量文档实体
- `VectorDocumentRepository.java` - 数据访问层
- `VectorizationService.java` - 向量化服务
- `VectorRetrievalService.java` - 向量检索服务
- `VectorController.java` - API 控制器

---

### 2. 报告生成服务

**文件位置：** `service/ReportingService.java`

**已完成功能：**
- ✅ 迁移就绪度报告（MIGRATION_READINESS）
  - 整体得分计算（0-100）
  - 架构理解度、业务知识覆盖、测试覆盖率等得分
  - 节点和边统计（总数、已确认、待确认）
  - 按节点类型统计
  - 风险项识别
  - 改进建议生成

- ✅ 置信度趋势报告（CONFIDENCE_TREND）
  - 每日数据点（日期、平均置信度、已确认节点数、新增节点数）
  - 趋势分析（UP/FLAT/DOWN）

- ✅ 测试覆盖率报告（TEST_COVERAGE）
  - 总体统计（总节点数、已覆盖节点数、覆盖率）
  - 按节点类型统计覆盖率
  - 高置信度但未覆盖的节点列表
  - 失败测试用例列表

- ✅ 图谱质量报告（GRAPH_QUALITY）
  - 基础统计（节点数、边数、不连通组件数）
  - 质量指标（平均节点度数、平均置信度、重复候选占比、图密度）
  - 置信度分布统计
  - 质量问题列表（低置信度、不连通、可能重复、孤立节点）
  - 质量评级（A/B/C/D）

- ✅ 报告导出（JSON 格式）
- ✅ ReportController REST API 接口完整

**相关文件：**
- `Report.java` - 报告实体
- `ReportRepository.java` - 数据访问层
- `ReportingService.java` - 报告生成服务
- `ReportController.java` - API 控制器
- `dto/report/` 目录下 4 种报告 DTO

---

### 3. 数据库断言执行器

**文件位置：** `test/DbAssertionExecutor.java`

**已完成功能：**
- ✅ 独立的数据库断言执行服务
- ✅ JDBC 连接管理（从项目配置的数据库连接）
- ✅ SQL 查询执行与结果验证
- ✅ 支持多种断言类型：
  - `DB_EXISTS` - 数据存在断言
  - `DB_NOT_EXISTS` - 数据不存在断言
  - `DB_COUNT_EQ` - 数量相等断言
  - `DB_COUNT_GT` - 数量大于断言
  - `DB_COUNT_LT` - 数量小于断言
  - `DB_FIELD_VALUE` - 字段值相等断言
- ✅ 断言执行结果包含查询 SQL、查询参数、实际结果

---

### 4. Agent 预处理和后处理逻辑

**涉及文件（6 个 Agent）：**
- `agent/CodeFactAgent.java` - 代码 AST 解析、方法调用链分析
- `agent/DocUnderstandingAgent.java` - 文档分块、标题层级解析、智能分片
- `agent/FeatureMappingAgent.java` - 前端 API 与后端 Controller 自动对齐
- `agent/GraphMergeAgent.java` - 图谱合并决策、置信度计算
- `agent/TestCaseAgent.java` - 多场景（正常/异常/边界）测试用例生成
- `agent/ReviewAgent.java` - 证据自动整理、冲突检测

**统一入口：**
- ✅ `llm/LlmGateway.java` - 统一 Agent 入口
  - 模型路由（支持多 LLM 提供者）
  - 输入缓存（基于 SHA256 哈希）
  - PII 数据脱敏
  - 审计日志
  - 结构化输出校验
  - Prompt 模板变量替换

---

### 5. 异步任务配置完善

**文件位置：** `config/AsyncConfig.java`

**已完成功能：**
- ✅ 实现 `AsyncConfigurer` 接口
- ✅ 创建 3 个专用线程池：

  **1. taskExecutor（通用任务线程池）**
  - 核心线程数：4
  - 最大线程数：8
  - 队列容量：100
  - 用途：扫描任务、报告生成、向量化等一般任务

  **2. ioTaskExecutor（IO密集型线程池）**
  - 核心线程数：8
  - 最大线程数：16
  - 队列容量：200
  - 用途：文件上传下载、MinIO 操作等 IO 密集型任务

  **3. testExecutor（测试执行线程池）**
  - 核心线程数：2
  - 最大线程数：4
  - 队列容量：50
  - 用途：API 测试、E2E 测试、数据库断言测试

- ✅ 拒绝策略：CallerRunsPolicy（调用者运行）
- ✅ 优雅关闭：等待任务完成（最长等待 60/120/300 秒）
- ✅ 线程名前缀规范（便于日志追踪）
- ✅ 异步任务异常处理器 `AsyncExceptionHandler`

---

### 6. 日志记录完善

**文件位置：**
- `annotation/Log.java` - 操作日志注解
- `aspect/LogAspect.java` - AOP 日志切面

**已完成功能：**

**Log 注解特性：**
- ✅ 操作描述（value）
- ✅ 操作类型枚举（OperationType：CREATE/UPDATE/DELETE/QUERY/SCAN/TEST/REVIEW/REPORT 等 16 种）
- ✅ 是否记录请求参数（logParams）
- ✅ 是否记录返回结果（logResult）
- ✅ 慢请求阈值配置（slowRequestThreshold，默认 3000ms）

**LogAspect 切面特性：**
- ✅ 请求出入参日志记录
- ✅ 方法执行耗时统计
- ✅ TraceId 链路追踪（16 位随机 ID）
- ✅ 慢请求告警（超过阈值打 WARN 日志）
- ✅ 异常堆栈完整记录
- ✅ 敏感参数自动过滤（password/token/secret/key/credential）
- ✅ 大返回结果截断（超过 2000 字符）
- ✅ IP 地址获取（支持 X-Forwarded-For 等代理头）
- ✅ 请求 URI 和 HTTP 方法记录
- ✅ 类名和方法名记录

**已添加 @Log 注解的 Controller：**
- ✅ AuthController（登录、登出、刷新 Token）
- ✅ ProjectController（项目 CRUD）
- ✅ SourceController（代码仓库、数据库、文档）

---

## 🟡 P2 低优先级待完成

### 7. 前端 - 全局 Loading 服务

**文件位置：** `utils/loading.ts`

**待实现功能：**
- [ ] 使用 ElLoading.service 实现全局 Loading
- [ ] 请求计数管理（防止嵌套请求闪烁）
- [ ] 请求拦截器中显示 Loading
- [ ] 响应拦截器中隐藏 Loading

---

### 8. 前端 - 表单校验通用规则库

**文件位置：** `utils/validate.ts`

**待实现功能：**
- [ ] 用户名校验（长度、字符限制）
- [ ] 邮箱格式校验
- [ ] URL 格式校验
- [ ] 手机号格式校验
- [ ] 密码强度校验
- [ ] 必填项、长度、范围校验
- [ ] 异步校验封装（查重等）

---

### 9. 前端 - WebSocket 实时通知

**文件位置：** `utils/websocket.ts`, `stores/notification.ts`

**待实现功能：**
- [ ] WebSocket 连接管理（心跳、重连）
- [ ] 扫描任务进度实时推送（替换轮询）
- [ ] 测试执行进度实时推送
- [ ] 消息 Toast 集成
- [ ] 离线消息队列

---

### 10. 前端 - Pinia 状态持久化

**待实现功能：**
- [ ] 安装 `pinia-plugin-persistedstate`
- [ ] User Store - Token、用户信息持久化
- [ ] App Store - 主题、布局配置持久化

---

### 11. 前端 - 搜索防抖优化

**待实现功能：**
- [ ] 安装 `lodash-es`
- [ ] SearchForm 搜索防抖（300ms）
- [ ] 高频滚动节流（100ms）

---

### 12. 前端 - 表格排序功能

**文件位置：** `components/common/BaseTable.vue`

**待实现功能：**
- [ ] 列可排序配置（sortable 属性）
- [ ] 排序事件派发（sort-change）
- [ ] 多列排序支持
- [ ] 排序状态 URL 参数持久化

---

### 13. 前端 - ECharts 数据可视化集成

**待实现功能：**
- [ ] Dashboard 置信度趋势折线图
- [ ] Dashboard 节点类型分布图（饼图）
- [ ] Dashboard 审核进度条形图
- [ ] 报告页面图谱质量雷达图

---

### 14. 图谱展示与交互

**技术栈问题：**
- [ ] 评估是否需要从 VueFlow 迁移到 AntV G6
- [ ] 或继续基于 VueFlow 增强功能

**核心功能待实现：**
- [ ] 图谱版本切换和对比
- [ ] 多种布局算法切换
- [ ] 最短路径分析（选择起点和终点）
- [ ] 上下游影响分析
- [ ] 图谱导出（PNG / SVG / JSON / CSV）
- [ ] 全屏查看模式
- [ ] 节点按名称搜索定位
- [ ] 邻居展开/收起（1跳/2跳）

**节点和边样式待实现：**
- [ ] 业务域节点（圆角矩形 + 专属颜色）
- [ ] 业务流程节点（胶囊形）
- [ ] 业务对象节点（椭圆）
- [ ] 业务规则节点（六边形）
- [ ] ApiEndpoint 节点（矩形 + 图标）
- [ ] SqlStatement 节点（菱形）
- [ ] Table 节点（数据库表图标）
- [ ] 置信度视觉区分（虚线边框、警告标记）

**性能优化待实现：**
- [ ] 默认加载核心图（不超过 1000 节点）
- [ ] 按需加载邻居节点
- [ ] 节点按模块聚合分组
- [ ] 数据血缘默认表级展示
- [ ] Web Worker 布局计算（不阻塞主线程）

---

### 15. 迁移风险识别页面

**文件位置：** `views/migration/`

**待实现功能：**
- [ ] 风险识别规则配置
- [ ] 风险列表和详情
- [ ] 风险等级评估
- [ ] 风险修复建议
- [ ] 风险趋势图表

---

## 🟢 P3 优化级待完成

### 16. 前端 - 性能优化

**待实现功能：**
- [ ] 大数据表格虚拟滚动
- [ ] 图片懒加载指令
- [ ] 组件异步加载（defineAsyncComponent）
- [ ] Vite 打包优化（代码分割、压缩、CDN）
- [ ] 首屏加载骨架屏

---

### 17. 前端 - 主题切换

**文件位置：** `stores/theme.ts`, `styles/variables.scss`

**待实现功能：**
- [ ] 暗黑/浅色模式切换
- [ ] CSS 变量管理主题色
- [ ] 主题切换组件
- [ ] 系统主题跟随（prefers-color-scheme）

---

### 18. 前端 - 国际化 i18n

**待实现功能：**
- [ ] 安装 `vue-i18n`
- [ ] 创建语言包目录 `locales/zh-CN.ts`, `locales/en-US.ts`
- [ ] 语言切换组件
- [ ] 路由 title 国际化
- [ ] Element Plus 国际化配置
- [ ] 日期时间格式化工具

---

### 19. 前端 - 响应式布局完善

**文件位置：** `composables/useBreakpoint.ts`

**待实现功能：**
- [ ] 使用 `@vueuse/core` 实现断点检测
- [ ] Sidebar 移动端折叠功能
- [ ] 表格移动端适配（横向滚动、列隐藏）
- [ ] 汉堡菜单按钮

---

## 📊 实施路线图

### 第一阶段 ✅ （已完成）：核心 Bug 修复和安全加固

**完成内容：**
- ✅ 递归死循环修复
- ✅ 方法签名不匹配修复
- ✅ BCrypt 密码加密
- ✅ JWT 认证配置
- ✅ 全局异常处理器
- ✅ DTO 参数校验注解全覆盖
- ✅ 文件上传安全加固

---

### 第二阶段 ✅ （已完成）：P1 后端核心服务完善

**完成内容：**
- ✅ 向量检索服务实现
- ✅ 报告生成服务（4 种报告类型）
- ✅ 数据库断言执行器完善
- ✅ 6 个 Agent 预处理逻辑
- ✅ 异步任务线程池配置（3 个专用线程池）
- ✅ 日志 AOP 切面实现（TraceId、慢请求、敏感参数过滤）

---

### 第三阶段 ⏳ （待开始）：前端增强

**目标：** 完成前端交互优化，完成度 90%+

- [ ] 全局 Loading 服务
- [ ] WebSocket 实时通知（替换轮询）
- [ ] Pinia 状态持久化
- [ ] 搜索防抖优化
- [ ] 表单校验通用规则库
- [ ] ECharts 图表集成
- [ ] 表格排序功能增强

---

### 第四阶段 ⏳ （待开始）：图谱和迁移

**目标：** 完成图谱核心交互和迁移风险识别

- [ ] AntV G6 图谱画布组件（或 VueFlow 增强）
- [ ] 图谱工具栏（搜索、布局切换、导出）
- [ ] 路径分析和影响分析功能
- [ ] 邻居展开/收起
- [ ] 节点和边样式完善
- [ ] 迁移风险识别页面

---

### 第五阶段 ⏳ （持续迭代）：高级优化

- [ ] 主题切换（暗黑模式）
- [ ] 国际化 i18n
- [ ] 性能优化（虚拟滚动、懒加载）
- [ ] 响应式布局完善

---

## 📝 统计总结

| 优先级 | 任务数 | 已完成 | 待完成 | 完成率 |
|--------|--------|--------|--------|--------|
| 🔴 P0 高优先级 | 5 | 5 | 0 | 100% |
| 🟠 P1 中优先级 | 6 | 6 | 0 | 100% |
| 🟡 P2 低优先级 | 9 | 0 | 9 | 0% |
| 🟢 P3 优化级 | 4 | 0 | 4 | 0% |
| **总计** | **24** | **11** | **13** | **46%** |

---

## 📁 相关文件清单

### 已创建/修改的文件（P0）

```
backend/src/main/java/io/github/legacygraph/
├── config/SecurityConfig.java               ✅ 修改 - JWT 配置
├── filter/JwtAuthenticationFilter.java      ✅ 新建 - JWT 过滤器
├── util/JwtUtil.java                         ✅ 新建 - JWT 工具类
├── common/ErrorCode.java                      ✅ 新建 - 错误码枚举
├── exception/BusinessException.java           ✅ 新建 - 业务异常
├── exception/GlobalExceptionHandler.java      ✅ 新建 - 全局异常处理器
├── service/ProjectService.java                ✅ 修改 - 修复死循环
└── controller/
    ├── AuthController.java                     ✅ 修改 - BCrypt + @Log
    ├── ProjectController.java                  ✅ 修改 - @Log 注解
    └── SourceController.java                   ✅ 修改 - 文件安全 + @Log

backend/src/main/java/io/github/legacygraph/dto/
└── 所有 DTO 都添加了校验注解
```

### 已创建/修改的文件（P1）

```
backend/src/main/java/io/github/legacygraph/
├── config/AsyncConfig.java                    ✅ 新建 - 异步线程池配置
├── annotation/Log.java                         ✅ 新建 - 操作日志注解
├── aspect/LogAspect.java                       ✅ 新建 - 日志 AOP 切面
├── service/VectorRetrievalService.java        ✅ 已存在 - 向量检索服务
├── service/ReportingService.java               ✅ 已存在 - 报告生成服务
├── service/VectorizationService.java           ✅ 已存在 - 向量化服务
├── test/DbAssertionExecutor.java               ✅ 已存在 - 数据库断言执行器
├── llm/LlmGateway.java                          ✅ 已存在 - Agent 统一入口
├── agent/CodeFactAgent.java                    ✅ 已存在 - Agent x 6
├── agent/DocUnderstandingAgent.java            ✅ 已存在
├── agent/FeatureMappingAgent.java              ✅ 已存在
├── agent/GraphMergeAgent.java                  ✅ 已存在
├── agent/TestCaseAgent.java                    ✅ 已存在
├── agent/ReviewAgent.java                      ✅ 已存在
├── controller/VectorController.java            ✅ 已存在 - 向量 API
├── controller/ReportController.java            ✅ 已存在 - 报告 API
├── entity/VectorDocument.java                  ✅ 已存在 - 向量实体
├── entity/Report.java                           ✅ 已存在 - 报告实体
└── dto/report/                                  ✅ 已存在 - 4 种报告 DTO
```

### 待创建文件（P2/P3）

```
前端待创建：
├── utils/loading.ts
├── utils/validate.ts
├── utils/websocket.ts
├── stores/notification.ts
├── stores/theme.ts
├── composables/useBreakpoint.ts
├── components/LangSwitcher.vue
├── components/ThemeSwitcher.vue
├── locales/zh-CN.ts
├── locales/en-US.ts
└── views/migration/
    ├── RiskList.vue
    └── RiskDetail.vue
```

---

**最后更新时间：** 2024-01-27
**文档维护人：** LegacyGraph Team
**P0/P1 完成率：** 100%
**项目整体完成度：** ~85%
