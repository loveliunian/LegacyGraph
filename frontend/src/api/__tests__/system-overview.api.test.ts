import { describe, expect, it, vi } from 'vitest'
import { downloadFile, get } from '@/utils/request'
import { exportSystemOverviewReport, getCorePaths } from '../system-overview.api'

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
})
