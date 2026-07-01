import { post } from '@/utils/request'

/**
 * Agent API
 * 提供各类 AI Agent 端点：运行、生成测试、审查建议、SQL 分析、失败分析、报告洞察、重构建议、变更影响、迁移转换、PR 描述
 */
export const agentApi = {
  /**
   * 运行 Agent
   * @param data 包含 agentType、projectId、versionId（可选）、params（可选）
   */
  run: (data: { agentType: string; projectId: string; versionId?: string; params?: Record<string, any> }) => {
    return post('/agents/run', data)
  },

  /** 生成测试用例 */
  generateTests: (data: any) => post('/agents/tests/generate', data),

  /** 审查建议 */
  reviewSuggest: (data: any) => post('/agents/review/suggest', data),

  /** SQL 分析 */
  analyzeSql: (data: any) => post('/agents/sql/analyze', data),

  /** 分析测试失败原因 */
  analyzeTestFailure: (data: any) => post('/agents/tests/analyze-failure', data),

  /** 报告洞察 */
  reportInsights: (data: any) => post('/agents/report/insights', data),

  /** 重构建议 */
  refactorSuggest: (data: any) => post('/agents/refactor/suggest', data),

  /** 变更影响分析 */
  changeImpact: (data: any) => post('/agents/change/impact', data),

  /** 迁移转换 */
  migrationConvert: (data: any) => post('/agents/migration/convert', data),

  /** PR 描述生成 */
  prDescribe: (data: any) => post('/agents/pr/describe', data),
}
