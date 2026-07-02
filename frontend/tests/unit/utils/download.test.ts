import { describe, it, expect, vi } from 'vitest'

describe('download 下载工具', () => {
  it('应该导出 downloadFile 函数', async () => {
    const mod = await import('@/utils/download')
    expect(mod.downloadFile).toBeDefined()
    expect(typeof mod.downloadFile).toBe('function')
  })

  it('应该导出 downloadBlob 函数', async () => {
    const mod = await import('@/utils/download')
    expect(mod.downloadBlob).toBeDefined()
    expect(typeof mod.downloadBlob).toBe('function')
  })

  it('downloadBlob 应该创建 Blob URL 并触发下载', async () => {
    const { downloadBlob } = await import('@/utils/download')
    const blob = new Blob(['test'], { type: 'text/plain' })

    const mockUrl = 'blob:test-url'
    const createObjectURL = vi.fn().mockReturnValue(mockUrl)
    const revokeObjectURL = vi.fn()
    const mockClick = vi.fn()

    // Mock URL static methods
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL,
      revokeObjectURL
    })

    const origCreateElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      if (tag === 'a') {
        return { href: '', download: '', click: mockClick, style: {} } as any
      }
      return origCreateElement(tag)
    })
    vi.spyOn(document.body, 'appendChild').mockImplementation(vi.fn() as any)
    vi.spyOn(document.body, 'removeChild').mockImplementation(vi.fn() as any)

    downloadBlob(blob, 'test.txt')

    expect(createObjectURL).toHaveBeenCalledWith(blob)
    expect(mockClick).toHaveBeenCalled()
  })
})
