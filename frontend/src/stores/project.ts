import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Project } from '@/types'
import { projectApi } from '@/api'

export const useProjectStore = defineStore('project', () => {
  const currentProjectId = ref<string | null>(localStorage.getItem('currentProjectId') || null)
  const currentProject = ref<Project | null>(null)
  const projectList = ref<Project[]>([])
  const loading = ref(false)

  const hasProject = computed(() => !!currentProjectId.value)

  const setCurrentProject = (projectId: string | null) => {
    currentProjectId.value = projectId
    if (projectId) {
      localStorage.setItem('currentProjectId', projectId)
    } else {
      localStorage.removeItem('currentProjectId')
    }
  }

  const fetchProjectList = async (params?: { pageNum?: number; pageSize?: number; keyword?: string }) => {
    loading.value = true
    try {
      const result: any = await projectApi.list({
        pageNum: 1,
        pageSize: 100,
        ...params
      })
      projectList.value = result.list
      return result
    } catch (error) {
      console.error('fetchProjectList error:', error)
      throw error
    } finally {
      loading.value = false
    }
  }

  const fetchCurrentProject = async () => {
    try {
      if (currentProjectId.value) {
        const project: any = await projectApi.detail(currentProjectId.value)
        currentProject.value = project
        return project
      }
      return null
    } catch (error) {
      console.error('fetchCurrentProject error:', error)
    }
  }

  const createProject = async (data: any) => {
    try {
      const project: any = await projectApi.create(data)
      projectList.value.unshift(project)
      return project
    } catch (error) {
      console.error('createProject error:', error)
    }
  }

  const deleteProject = async (projectId: string) => {
    try {
      await projectApi.delete(projectId)
      projectList.value = projectList.value.filter(p => p.id !== projectId)
      if (currentProjectId.value === projectId) {
        setCurrentProject(null)
        currentProject.value = null
      }
    } catch (error) {
      console.error('deleteProject error:', error)
    }
  }

  return {
    currentProjectId,
    currentProject,
    projectList,
    loading,
    hasProject,
    setCurrentProject,
    fetchProjectList,
    fetchCurrentProject,
    createProject,
    deleteProject
  }
})
