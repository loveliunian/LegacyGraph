import { del, get, post } from '@/utils/request'
import type { MigrationReadinessReport } from '@/types'

/**
 * 报告实体
 * 系统生成的各类分析报告
 */
export interface Report {
  /** 报告ID */
  id: string
  /** 关联项目ID */
  projectId: string
  /** 关联扫描版本ID */
  versionId: string
  /** 报告类型 */
  reportType: string
  /** 报告名称 */
  reportName: string
  /** 生成状态 */
  status: string
  /** 开始生成时间 */
  generatedAt: string
  /** 完成时间 */
  completedAt: string
}

/**
 * 报告API
 * 支持生成各类分析报告，并提供下载功能
 */
export const reportApi = {
  /**
   * 获取项目的报告列表
   * @param projectId 项目ID
   * @returns 报告列表
   */
  listReports: (projectId: string) => {
    return get<Report[]>(`/lg/projects/${encodeURIComponent(projectId)}/reports/list`)
  },

  /**
   * 删除报告（含本地文件，硬删除不可恢复）
   * @param projectId 项目ID
   * @param reportId 报告ID
   * @returns 是否删除成功（false 表示记录不存在）
   */
  deleteReport: (projectId: string, reportId: string) => {
    return del<boolean>(`/lg/projects/${encodeURIComponent(projectId)}/reports/${encodeURIComponent(reportId)}`)
  },

  /**
   * 生成迁移就绪度分析报告
   * 分析项目的迁移难度和就绪程度，给出评估结果
   * @param projectId 项目ID
   * @returns 迁移就绪度报告
   */
  generateMigrationReport: (projectId: string) => {
    return post<MigrationReadinessReport>(`/lg/projects/${encodeURIComponent(projectId)}/reports/migration-readiness/generate`)
  },

  /**
   * 生成置信度趋势报告
   * 分析知识图谱节点置信度随时间变化的趋势
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   * @returns 生成结果
   */
  generateConfidenceTrend: (projectId: string, versionId: string) => {
    return post(`/lg/projects/${encodeURIComponent(projectId)}/reports/confidence-trend/generate`, { versionId })
  },

  /**
   * 生成测试覆盖率报告
   * 分析知识图谱覆盖的功能范围和测试覆盖情况
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   * @returns 生成结果
   */
  generateTestCoverage: (projectId: string, versionId: string) => {
    return post(`/lg/projects/${encodeURIComponent(projectId)}/reports/test-coverage/generate`, { versionId })
  },

  /**
   * 生成知识图谱质量报告
   * 分析知识图谱的完整性、一致性、准确性等质量指标
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   * @returns 生成结果
   */
  generateGraphQuality: (projectId: string, versionId: string) => {
    return post(`/lg/projects/${encodeURIComponent(projectId)}/reports/graph-quality/generate`, { versionId })
  },

  /**
   * 下载报告文件
   * @param projectId 项目ID
   * @param reportId 报告ID
   * @param format 下载格式：pdf|html|markdown
   * @returns 报告文件Blob
   */
  downloadReport: (projectId: string, reportId: string, format: string) => {
    return get<Blob>(`/lg/projects/${encodeURIComponent(projectId)}/reports/${encodeURIComponent(reportId)}/download?format=${encodeURIComponent(format)}`, {
      responseType: 'blob'
    })
  },

  /**
   * 获取图谱指标
   * @param projectId 项目ID
   */
  getGraphMetrics: (projectId: string) => {
    return get(`/lg/projects/${encodeURIComponent(projectId)}/reports/graph-metrics`)
  },

  /**
   * 获取报告洞察
   * @param projectId 项目ID
   */
  getInsights: (projectId: string, versionId?: string, config?: Record<string, any>) => {
    const url = `/lg/projects/${projectId}/reports/insights` + (versionId ? `?versionId=${versionId}` : '')
    return get(url, config)
  },

  /**
   * 获取支持的导出格式列表
   */
  getExportFormats: () => {
    return get('/reports/formats')
  },

  /**
   * 导出迁移就绪度报告
   * @param projectId 项目ID
   */
  exportMigrationReport: (projectId: string) => {
    return get(`/reports/migration/${encodeURIComponent(projectId)}`)
  },

  /**
   * 导出置信度报告
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   */
  exportConfidenceReport: (projectId: string, versionId: string) => {
    return get(`/reports/confidence/${encodeURIComponent(projectId)}/${encodeURIComponent(versionId)}`)
  },

  /**
   * 导出测试覆盖率报告
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   */
  exportTestCoverageReport: (projectId: string, versionId: string) => {
    return get(`/reports/test-coverage/${encodeURIComponent(projectId)}/${encodeURIComponent(versionId)}`)
  },

  /**
   * 导出图谱质量报告
   * @param projectId 项目ID
   * @param versionId 扫描版本ID
   */
  exportGraphQualityReport: (projectId: string, versionId: string) => {
    return get(`/reports/graph-quality/${encodeURIComponent(projectId)}/${encodeURIComponent(versionId)}`)
  },

  /**
   * 导出功能切片报告
   * @param projectId 项目ID
   * @param sliceId 功能切片ID
   */
  exportFeatureSliceReport: (projectId: string, sliceId: string) => {
    return get(`/reports/feature-slice/${encodeURIComponent(projectId)}/${encodeURIComponent(sliceId)}`)
  },

  /**
   * 导出变更任务报告
   * @param projectId 项目ID
   * @param taskId 变更任务ID
   */
  exportChangeTaskReport: (projectId: string, taskId: string) => {
    return get(`/reports/change-task/${encodeURIComponent(projectId)}/${encodeURIComponent(taskId)}`)
  }
}
