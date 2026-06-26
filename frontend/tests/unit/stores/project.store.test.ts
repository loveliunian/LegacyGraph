import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useProjectStore } from '@/stores/project'

// Mock localStorage
const localStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    getItem: (key: string) => store[key] || null,
    setItem: (key: string, value: string) => { store[key] = value },
    removeItem: (key: string) => delete store[key],
    clear: () => { store = {} }
  }
})()

Object.defineProperty(window, 'localStorage', {
  value: localStorageMock
})

describe('Project Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorageMock.clear()
  })

  it('should initialize with default values', () => {
    const store = useProjectStore()
    expect(store.currentProjectId).toBeNull()
    expect(store.currentProject).toBeNull()
    expect(store.projectList).toEqual([])
    expect(store.hasProject).toBe(false)
  })

  it('should set current project', () => {
    const store = useProjectStore()
    store.setCurrentProject('test-project-id')
    expect(store.currentProjectId).toBe('test-project-id')
  })

  it('should clear current project when set to null', () => {
    const store = useProjectStore()
    store.setCurrentProject('test-project-id')
    store.setCurrentProject(null)
    expect(store.currentProjectId).toBeNull()
  })

  it('should update hasProject correctly', () => {
    const store = useProjectStore()
    expect(store.hasProject).toBe(false)
    store.setCurrentProject('test-project-id')
    expect(store.hasProject).toBe(true)
  })
})
