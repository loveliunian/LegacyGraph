import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Project } from '@/types'
import { projectApi } from '@/api'

export const useProjectStore = defineStore('project', () => {
  const currentProjectId = ref<string | null>(localStorage.getItem('currentProjectId') || null)
  const currentProject = ref<Project | null>(null)
  const projectList = ref<Project[]>([])

  const hasProject = computed(() => !!currentProjectId.value)

  const setCurrentProject = (projectId: string | null) => {
    currentProjectId.value = projectId
    if (projectId) {
      localStorage.setItem('currentProjectId', projectId)
    } else {
      localStorage.removeItem('currentProjectId')
    }
  }

  const fetchProjectList = async (params?: { keyword?: string }) => {
    const result: any = await projectApi.list({
      pageNum: 1,
      pageSize: 100,
      ...params
    })
    projectList.value = result.list
    return result
  }

  const fetchCurrentProject = async () => {
    if (currentProjectId.value) {
      const project: any = await projectApi.detail(currentProjectId.value)
      currentProject.value = project
      return project
    }
    return null
  }

  const createProject = async (data: any) => {
    const project: any = await projectApi.create(data)
    projectList.value.unshift(project)
    return project
  }

  const deleteProject = async (projectId: string) => {
    await projectApi.delete(projectId)
    projectList.value = projectList.value.filter(p => p.id !== projectId)
    if (currentProjectId.value === projectId) {
      setCurrentProject(null)
      currentProject.value = null
    }
  }

  return {
    currentProjectId,
    currentProject,
    projectList,
    hasProject,
    setCurrentProject,
    fetchProjectList,
    fetchCurrentProject,
    createProject,
    deleteProject
  }
})
