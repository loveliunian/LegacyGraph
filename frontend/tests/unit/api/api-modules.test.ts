import { describe, it, expect, vi } from 'vitest'

// Mock axios instance
vi.mock('@/utils/request', () => ({
  default: {
    get: vi.fn().mockResolvedValue({ data: [] }),
    post: vi.fn().mockResolvedValue({ data: {} }),
    put: vi.fn().mockResolvedValue({ data: {} }),
    delete: vi.fn().mockResolvedValue({ data: {} })
  },
  get: vi.fn().mockResolvedValue({ data: [] }),
  post: vi.fn().mockResolvedValue({ data: {} })
}))

describe('API 模块', () => {
  it('agent.api 应该导出 agentApi', async () => {
    const mod = await import('@/api/agent.api')
    expect(mod.agentApi).toBeDefined()
  })

  it('audit.api 应该导出 auditApi', async () => {
    const mod = await import('@/api/audit.api')
    expect(mod.auditApi).toBeDefined()
  })

  it('auth.api 应该导出 authApi', async () => {
    const mod = await import('@/api/auth.api')
    expect(mod.authApi).toBeDefined()
  })

  it('change-task.api 应该导出 changeTaskApi', async () => {
    const mod = await import('@/api/change-task.api')
    expect(mod.changeTaskApi).toBeDefined()
  })

  it('fact.api 应该导出 factApi', async () => {
    const mod = await import('@/api/fact.api')
    expect(mod.factApi).toBeDefined()
  })

  it('llm.api 应该导出 llmApi', async () => {
    const mod = await import('@/api/llm.api')
    expect(mod.llmApi).toBeDefined()
  })

  it('prompt.api 应该导出 promptApi', async () => {
    const mod = await import('@/api/prompt.api')
    expect(mod.promptApi).toBeDefined()
  })

  it('qa.api 应该导出 qaApi', async () => {
    const mod = await import('@/api/qa.api')
    expect(mod.qaApi).toBeDefined()
  })

  it('report.api 应该导出 reportApi', async () => {
    const mod = await import('@/api/report.api')
    expect(mod.reportApi).toBeDefined()
  })

  it('source.api 应该导出 sourceApi', async () => {
    const mod = await import('@/api/source.api')
    expect(mod.sourceApi).toBeDefined()
  })

  it('system.api 应该导出 systemApi', async () => {
    const mod = await import('@/api/system.api')
    expect(mod.systemApi).toBeDefined()
  })

  it('test-run.api 应该导出 testRunApi', async () => {
    const mod = await import('@/api/test-run.api')
    expect(mod.testRunApi).toBeDefined()
  })

  it('trace.api 应该导出 traceApi', async () => {
    const mod = await import('@/api/trace.api')
    expect(mod.traceApi).toBeDefined()
  })

  it('vector.api 应该导出 vectorApi', async () => {
    const mod = await import('@/api/vector.api')
    expect(mod.vectorApi).toBeDefined()
  })

  it('index.ts barrel 应该导出所有模块', async () => {
    const mod = await import('@/api/index')
    expect(mod).toBeDefined()
    // 至少有几个已知的导出
    const exports = Object.keys(mod)
    expect(exports.length).toBeGreaterThan(0)
  })
})
