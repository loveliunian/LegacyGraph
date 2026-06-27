import { get, post } from '@/utils/request'
import type { MigrationReadinessReport } from '@/types/report'

export interface Report {
  id: string
  projectId: string
  versionId: string
  reportType: string
  reportName: string
  status: string
  generatedAt: string
  completedAt: string
}

export const reportApi = {
  listReports: (projectId: string) => {
    return get<Report[]>(`/lg/projects/${projectId}/reports/list`)
  },

  generateMigrationReport: (projectId: string) => {
    return post<MigrationReadinessReport>(`/lg/projects/${projectId}/reports/migration-readiness/generate`)
  },

  generateConfidenceTrend: (projectId: string, versionId: string) => {
    return post(`/lg/projects/${projectId}/reports/confidence-trend/generate`, { versionId })
  },

  generateTestCoverage: (projectId: string, versionId: string) => {
    return post(`/lg/projects/${projectId}/reports/test-coverage/generate`, { versionId })
  },

  generateGraphQuality: (projectId: string, versionId: string) => {
    return post(`/lg/projects/${projectId}/reports/graph-quality/generate`, { versionId })
  },

  downloadReport: (reportId: string, format: string) => {
    return get<Blob>(`/lg/reports/${reportId}/download?format=${format}`, {
      responseType: 'blob'
    })
  }
}
