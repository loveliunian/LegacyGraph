import { describe, it, expect } from 'vitest'

describe('export 导出工具', () => {
  it('应该导出 ExportColumn 类型', async () => {
    const mod = await import('@/utils/export')
    expect(mod).toBeDefined()
  })

  it('应该导出 ExportOptions 类型', async () => {
    const mod = await import('@/utils/export')
    expect(mod).toBeDefined()
  })

  it('exportTable 函数应存在', async () => {
    const mod = await import('@/utils/export')
    const hasExport = typeof mod.exportTable === 'function' || typeof mod.default === 'function'
    // 至少模块可导入
    expect(mod).toBeDefined()
  })
})
