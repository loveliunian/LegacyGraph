import { get, post } from '@/utils/request'

/**
 * 变更任务 API
 * 支持创建变更任务、查询详情、刷新影响分析、生成补丁、运行验证
 */
export const changeTaskApi = {
  /**
   * 查询项目下的变更任务
   * @param projectId 项目ID
   */
  list: (projectId: string) => get('/change-tasks', { projectId }),

  /**
   * 创建变更任务
   * @param data 包含 projectId、versionId、taskType、title、inputIssue
   */
  create: (data: { projectId: string; versionId: string; taskType: string; title: string; inputIssue: string }) => {
    return post('/change-tasks', data)
  },

  /**
   * 获取变更任务详情
   * @param id 任务ID
   */
  get: (id: string) => get(`/change-tasks/${id}`),

  /**
   * 刷新影响分析
   * @param id 任务ID
   * @param targetNodeId 目标节点ID
   */
  refreshImpact: (id: string, targetNodeId: string) => post(`/change-tasks/${id}/impact`, { targetNodeId }),

  /**
   * 生成补丁
   * @param id 任务ID
   * @param data 补丁参数
   */
  generatePatch: (id: string, data: any) => post(`/change-tasks/${id}/generate-patch`, data),

  /**
   * 运行验证
   * @param id 任务ID
   * @param data 验证参数
   */
  runValidation: (id: string, data: { gateTypes?: string[]; caseIds?: string[]; workingDir?: string; environment?: string }) => {
    return post(`/change-tasks/${id}/run-validation`, data)
  },

  /**
   * 创建 PR 草案。后端会在门禁未通过时拒绝。
   * @param id 变更任务 ID
   */
  createPr: (id: string) => post(`/change-tasks/${id}/create-pr`),
}
