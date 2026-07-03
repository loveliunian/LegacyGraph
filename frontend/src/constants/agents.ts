import type { Component } from 'vue'
import {
  MagicStick, Document, Connection, Search,
  TrendCharts, RefreshRight, WarningFilled,
  Switch, EditPen,
} from '@element-plus/icons-vue'

/** Agent 类型标识 —— 与后端 @PostMapping 路径对应 */
export type AgentType =
  | 'general'   // /agents/run (通用路由)
  | 'sql'       // /agents/sql/analyze
  | 'test'      // /agents/tests/generate
  | 'review'    // /agents/review/suggest
  | 'failure'   // /agents/tests/analyze-failure
  | 'report'    // /agents/report/insights
  | 'refactor'  // /agents/refactor/suggest
  | 'change'    // /agents/change/impact
  | 'migration' // /agents/migration/convert
  | 'pr'        // /agents/pr/describe

export interface AgentDef {
  type: AgentType
  title: string
  desc: string
  icon: Component
  tag: string
  tagType: 'primary' | 'success' | 'warning' | 'info' | 'danger'
  /** 使用场景 — 卡片副标题 */
  scene: string
  /** 需要的输入 — 卡片副标题 */
  inputHint: string
}

/** 全部 10 项 AI 能力定义 */
export const AGENTS: AgentDef[] = [
  {
    type: 'general', title: '通用 Agent', desc: '代码事实抽取、文档理解、功能映射、测试生成',
    icon: MagicStick, tag: '基础', tagType: 'primary',
    scene: '适合：不熟悉具体用哪个 Agent 时，从这里开始',
    inputHint: '需要：选子类型 + JSON 参数',
  },
  {
    type: 'sql', title: 'SQL 分析', desc: '诊断 SQL 性能问题，给出优化建议和改写后的 SQL',
    icon: Search, tag: '数据库', tagType: 'warning',
    scene: '适合：慢查询排查、SQL Review、上线前检查',
    inputHint: '需要：SQL 语句 + 可选的表结构',
  },
  {
    type: 'test', title: '测试生成', desc: '根据功能描述自动生成测试用例和测试数据',
    icon: Document, tag: '测试', tagType: 'success',
    scene: '适合：新功能上线前补测试、遗留代码补用例',
    inputHint: '需要：功能标识 + API 端点 + 业务规则',
  },
  {
    type: 'review', title: '审核建议', desc: '对图谱中的待审核项给出 AI 审核意见和评分',
    icon: TrendCharts, tag: '审核', tagType: 'info',
    scene: '适合：知识主张审核、图谱节点质量检查',
    inputHint: '需要：目标 ID + 待审核内容',
  },
  {
    type: 'failure', title: '失败分析', desc: '测试失败的根因定位、排查步骤和复测建议',
    icon: WarningFilled, tag: '测试', tagType: 'danger',
    scene: '适合：CI 挂了不知道原因、偶发失败排查',
    inputHint: '需要：失败用例名 + 错误堆栈',
  },
  {
    type: 'report', title: '报告洞察', desc: '根据图谱扫描指标生成优先级排序的行动清单',
    icon: TrendCharts, tag: '报告', tagType: 'success',
    scene: '适合：扫描完成后不知道从哪开始修',
    inputHint: '需要：扫描指标数据 + 缺口描述',
  },
  {
    type: 'refactor', title: '重构建议', desc: '分析代码职责边界，给出拆分方案和重构骨架',
    icon: RefreshRight, tag: '代码', tagType: 'warning',
    scene: '适合：类太大了、方法太长了、代码重复了',
    inputHint: '需要：文件路径 + 异味类型',
  },
  {
    type: 'change', title: '变更影响', desc: '判断代码变更的影响范围、严重程度和回归范围',
    icon: Connection, tag: '分析', tagType: 'danger',
    scene: '适合：改关键代码前评估风险',
    inputHint: '需要：变更目标 + 变更描述',
  },
  {
    type: 'migration', title: '迁移转换', desc: '按迁移规则自动转换代码（框架升级/语法迁移）',
    icon: Switch, tag: '迁移', tagType: 'primary',
    scene: '适合：Struts→Spring、Java 8→17、XML→注解',
    inputHint: '需要：选迁移方向 + 粘贴源码',
  },
  {
    type: 'pr', title: 'PR 描述', desc: '根据代码变更自动生成规范的提交信息和 PR 描述',
    icon: EditPen, tag: '协作', tagType: 'info',
    scene: '适合：写完代码不想手动写 PR 描述',
    inputHint: '需要：分支名 + git diff 输出',
  },
]

/** Agent 总数 */
export const AGENT_COUNT = AGENTS.length

/** 快速参考表：帮助操作员理解该用什么能力 */
export interface QuickRefEntry {
  want: string
  use: string
  need: string
}

export const QUICK_REF: QuickRefEntry[] = [
  { want: '分析一段 SQL 有没有性能问题', use: 'SQL 分析', need: 'SQL 语句（可从代码或日志中复制）' },
  { want: '给一个功能自动生成测试用例', use: '测试生成', need: '功能名称 + API 端点 + 业务规则' },
  { want: '查一个测试为什么失败了', use: '失败分析', need: '失败用例名 + 错误信息' },
  { want: '看看扫描报告里哪些问题要优先修', use: '报告洞察', need: '图谱指标数据（从报告页复制）' },
  { want: '一个类太大了不知道怎么拆', use: '重构建议', need: '文件名 + 异味类型' },
  { want: '改一个方法会影响哪些地方', use: '变更影响', need: '方法名 + 变更说明' },
  { want: '把老框架代码转成新框架', use: '迁移转换', need: '选迁移方向 + 粘贴源码' },
  { want: '写个规范的 Git 提交信息', use: 'PR 描述', need: '分支名 + git diff 输出' },
]

/** 重构异味类型选项 */
export const REFACTOR_SMELL_OPTIONS = [
  { label: '上帝类 — 一个类承担了太多职责', value: 'god_class' },
  { label: '长方法 — 方法过长难以理解和测试', value: 'long_method' },
  { label: '重复代码 — 多处出现相同或相似的代码', value: 'duplication' },
  { label: '特性依恋 — 方法过多使用其他类的数据', value: 'feature_envy' },
  { label: '数据泥团 — 多个数据项总是一起出现应封装为对象', value: 'data_clumps' },
]

/** 迁移方向选项 */
export const MIGRATION_DIRECTION_OPTIONS = [
  { label: 'Struts → Spring MVC — 传统 MVC 迁移到 Spring', value: 'struts2spring' },
  { label: 'MyBatis → JPA — SQL 映射迁移到 ORM', value: 'mybatis2jpa' },
  { label: 'XML 配置 → 注解 — Spring XML 迁移到注解配置', value: 'xml2annotation' },
  { label: 'Java 8 → Java 17 — 升级到新版本语法特性', value: 'java8to17' },
]

/** 通用 Agent 子类型选项 */
export const GENERAL_AGENT_TYPES = [
  { label: '代码事实抽取 — 从代码中提取类/方法/依赖等结构化事实', value: 'codefact' },
  { label: '文档理解 — 从需求文档/设计文档中提取业务事实', value: 'docunderstanding' },
  { label: '功能映射 — 建立前端页面↔后端API↔业务功能的映射关系', value: 'featuremapping' },
  { label: '测试用例生成 — 根据功能描述自动生成测试场景', value: 'testcasegeneration' },
] as const
