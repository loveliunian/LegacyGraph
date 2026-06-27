import { get, post, put, del } from '@/utils/request'
import type { PageResult } from '@/types'

export interface User {
  id: string
  username: string
  nickname: string
  email: string
  avatar: string
  status: string
  permissions: string[]
  createdAt: string
}

export interface Dictionary {
  id: string
  dictType: string
  dictCode: string
  dictName: string
  dictValue: string
  sort: number
  status: string
}

export interface SystemConfig {
  id: string
  configKey: string
  configValue: string
  configDesc: string
}

export const systemApi = {
  // 用户管理
  listUsers: (params: {
    pageNum: number
    pageSize: number
    keyword?: string
    status?: string
  }) => {
    return get<PageResult<User>>('/lg/system/users', params)
  },

  createUser: (data: Partial<User>) => {
    return post<User>('/lg/system/users', data)
  },

  updateUser: (id: string, data: Partial<User>) => {
    return put(`/lg/system/users/${id}`, data)
  },

  deleteUser: (id: string) => {
    return del(`/lg/system/users/${id}`)
  },

  // 字典管理
  listDictionaries: (params: {
    pageNum: number
    pageSize: number
    dictType?: string
  }) => {
    return get<PageResult<Dictionary>>('/lg/system/dictionaries', params)
  },

  createDictionary: (data: Partial<Dictionary>) => {
    return post<Dictionary>('/lg/system/dictionaries', data)
  },

  updateDictionary: (id: string, data: Partial<Dictionary>) => {
    return put(`/lg/system/dictionaries/${id}`, data)
  },

  deleteDictionary: (id: string) => {
    return del(`/lg/system/dictionaries/${id}`)
  },

  // 系统配置
  listConfigs: (params: {
    pageNum: number
    pageSize: number
    configKey?: string
  }) => {
    return get<PageResult<SystemConfig>>('/lg/system/configs', params)
  },

  updateConfig: (id: string, data: Partial<SystemConfig>) => {
    return put(`/lg/system/configs/${id}`, data)
  }
}
