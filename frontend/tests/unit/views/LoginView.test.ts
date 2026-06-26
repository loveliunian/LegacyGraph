import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import LoginView from '@/views/login/LoginPage.vue'

// Mock Element Plus
vi.mock('element-plus', async () => {
  const original = await vi.importActual('element-plus')
  return {
    ...original,
    ElMessage: {
      success: vi.fn(),
      error: vi.fn()
    }
  }
})

describe('LoginPage View', () => {
  let router: any
  let pinia: any

  beforeEach(() => {
    setActivePinia(createPinia())
    pinia = createPinia()
    router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/login', name: 'Login', component: LoginView },
        { path: '/projects', name: 'Projects', component: { template: '<div>Projects</div>' } }
      ]
    })
  })

  it('should render correctly', () => {
    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-form', 'el-form-item', 'el-input', 'el-button', 'el-icon']
      }
    })
    expect(wrapper.find('.login-container').exists()).toBe(true)
  })

  it('should have login form elements', () => {
    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-form', 'el-form-item', 'el-input', 'el-button', 'el-icon']
      }
    })
    expect(wrapper.find('form').exists()).toBe(true)
  })

  it('should have submit button', () => {
    const wrapper = mount(LoginView, {
      global: {
        plugins: [router, pinia],
        stubs: ['el-form', 'el-form-item', 'el-input', 'el-button', 'el-icon']
      }
    })
    expect(wrapper.find('button').exists()).toBe(true)
  })
})
