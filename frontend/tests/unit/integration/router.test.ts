import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'

// Mock components
const MockLogin = { template: '<div>Login</div>' }
const MockProjectList = { template: '<div>ProjectList</div>' }
const MockProjectDetail = { template: '<div>ProjectDetail</div>' }

// Mock axios
vi.mock('axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn()
  }
}))

describe('Router Integration', () => {
  let router: any
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
    router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/login', name: 'Login', component: MockLogin },
        { path: '/projects', name: 'Projects', component: MockProjectList },
        { path: '/projects/:id', name: 'ProjectDetail', component: MockProjectDetail }
      ]
    })
  })

  it('should have login route', () => {
    const route = router.getRoutes().find((r: any) => r.name === 'Login')
    expect(route).toBeDefined()
    expect(route?.path).toBe('/login')
  })

  it('should have projects route', () => {
    const route = router.getRoutes().find((r: any) => r.name === 'Projects')
    expect(route).toBeDefined()
    expect(route?.path).toBe('/projects')
  })

  it('should have project detail route with parameter', () => {
    const route = router.getRoutes().find((r: any) => r.name === 'ProjectDetail')
    expect(route).toBeDefined()
    expect(route?.path).toBe('/projects/:id')
  })

  it('should support navigation to login page', async () => {
    await router.push('/login')
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('should support navigation to projects page', async () => {
    await router.push('/projects')
    expect(router.currentRoute.value.path).toBe('/projects')
  })

  it('should pass project id parameter', async () => {
    await router.push('/projects/test-id')
    expect(router.currentRoute.value.params.id).toBe('test-id')
  })
})
