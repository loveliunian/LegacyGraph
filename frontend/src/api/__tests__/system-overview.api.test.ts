import { describe, expect, it, vi } from 'vitest'
import { downloadFile, get, post } from '@/utils/request'
import {
  downloadSystemOverviewDocument,
  exportSystemOverviewReport,
  generateSystemOverviewDocument,
  getCorePaths,
} from '../system-overview.api'

vi.mock('@/utils/request', () => ({
  get: vi.fn(),
  post: vi.fn(),
  downloadFile: vi.fn(),
}))

describe('system overview api', () => {
  it('passes from and to filters to core paths endpoint', () => {
    getCorePaths('project-1', 'v1', 'Feature', 'Table')

    expect(get).toHaveBeenCalledWith('/lg/projects/project-1/system-overview/paths', {
      versionId: 'v1',
      from: 'Feature',
      to: 'Table',
    })
  })

  it('downloads system overview report from report endpoint', () => {
    exportSystemOverviewReport('project-1', 'v1', 'MD')

    expect(downloadFile).toHaveBeenCalledWith('/reports/system-overview/project-1/v1', {
      format: 'MD',
    })
  })

  it('generates persisted system overview document for existing scans', () => {
    generateSystemOverviewDocument('project-1', 'v1')

    expect(post).toHaveBeenCalledWith(
      '/lg/projects/project-1/system-overview/reports/generate',
      {},
      { params: { versionId: 'v1' } },
    )
  })

  it('downloads persisted system overview document by report id', () => {
    downloadSystemOverviewDocument('project-1', 'report-1', 'MD')

    expect(downloadFile).toHaveBeenCalledWith('/lg/projects/project-1/reports/report-1/download', {
      format: 'MD',
    })
  })
})
