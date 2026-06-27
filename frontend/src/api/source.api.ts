import { get, post, put, del } from '@/utils/request'
import type { PageResult, PageQuery } from '@/types'

export interface CodeRepo {
  id: string
  projectId: string
  repoUrl: string
  branchName: string
  localPath: string
  status: string
  createdAt: string
}

export interface DbConnection {
  id: string
  projectId: string
  connectionName: string
  dbType: string
  host: string
  port: number
  databaseName: string
  username: string
  status: string
  createdAt: string
}

export interface Document {
  id: string
  projectId: string
  docName: string
  docType: string
  fileUrl: string
  status: string
  createdAt: string
}

export const sourceApi = {
  // 代码仓库
  listCodeRepo: (projectId: string, params: PageQuery) => {
    return get<PageResult<CodeRepo>>(`/lg/projects/${projectId}/code-repos`, params)
  },

  createCodeRepo: (projectId: string, data: Partial<CodeRepo>) => {
    return post<CodeRepo>(`/lg/projects/${projectId}/code-repos`, data)
  },

  updateCodeRepo: (projectId: string, id: string, data: Partial<CodeRepo>) => {
    return put(`/lg/projects/${projectId}/code-repos/${id}`, data)
  },

  deleteCodeRepo: (projectId: string, id: string) => {
    return del(`/lg/projects/${projectId}/code-repos/${id}`)
  },

  testConnection: (id: string) => {
    return post<{ success: boolean; message: string }>(`/lg/db-connections/${id}/test`)
  },

  // 数据库连接
  listDbConnections: (projectId: string, params: PageQuery) => {
    return get<PageResult<DbConnection>>(`/lg/projects/${projectId}/db-connections`, params)
  },

  createDbConnection: (projectId: string, data: Partial<DbConnection>) => {
    return post<DbConnection>(`/lg/projects/${projectId}/db-connections`, data)
  },

  updateDbConnection: (projectId: string, id: string, data: Partial<DbConnection>) => {
    return put(`/lg/projects/${projectId}/db-connections/${id}`, data)
  },

  deleteDbConnection: (projectId: string, id: string) => {
    return del(`/lg/projects/${projectId}/db-connections/${id}`)
  },

  // 文档
  listDocuments: (projectId: string, params: PageQuery) => {
    return get<PageResult<Document>>(`/lg/projects/${projectId}/documents`, params)
  },

  createDocument: (projectId: string, data: Partial<Document>) => {
    return post<Document>(`/lg/projects/${projectId}/documents`, data)
  },

  deleteDocument: (projectId: string, id: string) => {
    return del(`/lg/projects/${projectId}/documents/${id}`)
  }
}
