import { get, post, put, del, upload } from '@/utils/request'
import type { PageResult, PageQuery } from '@/types'

/**
 * 代码仓库实体
 */
export interface CodeRepo {
  /** 仓库ID */
  id: string
  /** 关联项目ID */
  projectId: string
  /** 仓库名称 */
  repoName: string
  /** 仓库类型: BACKEND | FRONTEND | FULLSTACK */
  repoType: string
  /** Git仓库地址 */
  gitUrl: string
  /** 分支名称 */
  branchName: string
  /** 认证类型 */
  authType?: string
  /** 用户名 */
  username?: string
  /** 包含文件模式 */
  includePattern?: string
  /** 排除文件模式 */
  excludePattern?: string
  /** 全栈项目-后端子路径 */
  backendSubPath?: string
  /** 全栈项目-前端子路径 */
  frontendSubPath?: string
  /** 本地存储路径 */
  localPath: string
  /** 仓库状态 */
  status: string
  /** 最后拉取状态 */
  lastPullStatus?: string
  /** 创建时间 */
  createdAt: string
}

/**
 * 数据库连接实体
 */
export interface DbConnection {
  /** 连接ID */
  id: string
  /** 关联项目ID */
  projectId: string
  /** 连接名称 */
  connectionName: string
  /** 数据库类型 */
  dbType: string
  /** 数据库主机地址 */
  host: string
  /** 端口号 */
  port: number
  /** 数据库名称 */
  databaseName: string
  /** 用户名 */
  username: string
  /** 连接状态 */
  status: string
  /** 创建时间 */
  createdAt: string
}

/**
 * 上传文档实体
 */
export interface Document {
  /** 文档ID */
  id: string
  /** 关联项目ID */
  projectId: string
  /** 文档名称 */
  docName: string
  /** 文档类型 */
  docType: string
  /** 文件访问URL */
  fileUrl: string
  /** 文档状态 */
  status: string
  /** 创建时间 */
  createdAt: string
}

/**
 * 数据源接入API
 * 管理项目的三类数据源：代码仓库、数据库连接、文档资料
 */
export const sourceApi = {
  /**
   * 分页查询代码仓库列表
   * @param projectId 项目ID
   * @param params 分页查询参数
   * @returns 分页后的代码仓库列表
   */
  listCodeRepo: (projectId: string, params: PageQuery) => {
    return get<PageResult<CodeRepo>>(`/lg/projects/${encodeURIComponent(projectId)}/sources/repos`, params)
  },

  /**
   * 创建代码仓库
   * @param projectId 项目ID
   * @param data 代码仓库数据
   * @returns 创建的代码仓库信息
   */
  createCodeRepo: (projectId: string, data: Partial<CodeRepo>) => {
    return post<CodeRepo>(`/lg/projects/${encodeURIComponent(projectId)}/sources/repos`, data)
  },

  /**
   * 更新代码仓库
   * @param projectId 项目ID
   * @param id 代码仓库ID
   * @param data 更新数据
   * @returns 更新结果
   */
  updateCodeRepo: (projectId: string, id: string, data: Partial<CodeRepo>) => {
    return put(`/lg/projects/${encodeURIComponent(projectId)}/sources/repos/${encodeURIComponent(id)}`, data)
  },

  /**
   * 删除代码仓库
   * @param projectId 项目ID
   * @param id 代码仓库ID
   * @returns 删除结果
   */
  deleteCodeRepo: (projectId: string, id: string) => {
    return del(`/lg/projects/${projectId}/sources/repos/${id}`)
  },

  /**
   * 拉取代码仓库代码
   * @param projectId 项目ID
   * @param id 仓库ID
   * @returns 拉取结果
   */
  pullRepo: (projectId: string, id: string) => {
    return post<{ success: boolean; message: string }>(`/lg/projects/${encodeURIComponent(projectId)}/sources/repos/${encodeURIComponent(id)}/pull`)
  },

  /**
   * 扫描代码仓库
   * @param projectId 项目ID
   * @param id 仓库ID
   * @returns 扫描版本ID
   */
  scanRepo: (projectId: string, id: string) => {
    return post<{ versionId: string; message: string }>(`/lg/projects/${encodeURIComponent(projectId)}/sources/repos/${encodeURIComponent(id)}/scan`)
  },

  /**
   * 测试代码仓库连接
   * @param projectId 项目ID
   * @param id 代码仓库ID
   * @returns 测试结果，包含成功标志和消息
   */
  testRepoConnection: (projectId: string, id: string) => {
    return post<{ success: boolean; message: string }>(`/lg/projects/${encodeURIComponent(projectId)}/sources/repos/${encodeURIComponent(id)}/test-connection`)
  },

  /**
   * 测试 Git URL 连通性（无需已保存的仓库）
   * @param projectId 项目ID
   * @param gitUrl Git仓库地址
   * @returns 测试结果
   */
  testRepoUrl: (projectId: string, gitUrl: string) => {
    return post<{ success: boolean; message: string }>(`/lg/projects/${encodeURIComponent(projectId)}/sources/repos/test-url`, { gitUrl })
  },

  /**
   * 测试数据库连接
   * @param projectId 项目ID
   * @param id 数据库连接ID
   * @returns 测试结果，包含成功标志和消息
   */
  testDbConnection: (projectId: string, id: string) => {
    return post<{ success: boolean; message: string }>(`/lg/projects/${encodeURIComponent(projectId)}/sources/databases/${encodeURIComponent(id)}/test-connection`)
  },

  /**
   * 扫描数据库表结构
   * @param projectId 项目ID
   * @param id 数据库连接ID
   * @returns 扫描结果
   */
  scanDbSchema: (projectId: string, id: string) => {
    return post<{ tableCount: number }>(`/lg/projects/${encodeURIComponent(projectId)}/sources/databases/${encodeURIComponent(id)}/scan-schema`)
  },

  /**
   * 解析文档
   * @param projectId 项目ID
   * @param id 文档ID
   * @returns 解析结果
   */
  parseDocument: (projectId: string, id: string) => {
    return post<{ factCount: number; chunkCount?: number; success?: boolean; message?: string }>(`/lg/projects/${encodeURIComponent(projectId)}/sources/documents/${encodeURIComponent(id)}/parse`)
  },

  /**
   * 分页查询数据库连接列表
   * @param projectId 项目ID
   * @param params 分页查询参数
   * @returns 分页后的数据库连接列表
   */
  listDbConnections: (projectId: string, params: PageQuery) => {
    return get<PageResult<DbConnection>>(`/lg/projects/${encodeURIComponent(projectId)}/sources/databases`, params)
  },

  /**
   * 创建数据库连接
   * @param projectId 项目ID
   * @param data 数据库连接数据
   * @returns 创建的数据库连接信息
   */
  createDbConnection: (projectId: string, data: Partial<DbConnection>) => {
    return post<DbConnection>(`/lg/projects/${encodeURIComponent(projectId)}/sources/databases`, data)
  },

  /**
   * 更新数据库连接
   * @param projectId 项目ID
   * @param id 数据库连接ID
   * @param data 更新数据
   * @returns 更新结果
   */
  updateDbConnection: (projectId: string, id: string, data: Partial<DbConnection>) => {
    return put(`/lg/projects/${encodeURIComponent(projectId)}/sources/databases/${encodeURIComponent(id)}`, data)
  },

  /**
   * 删除数据库连接
   * @param projectId 项目ID
   * @param id 数据库连接ID
   * @returns 删除结果
   */
  deleteDbConnection: (projectId: string, id: string) => {
    return del(`/lg/projects/${projectId}/sources/databases/${id}`)
  },

  /**
   * 分页查询文档列表
   * @param projectId 项目ID
   * @param params 分页查询参数
   * @returns 分页后的文档列表
   */
  listDocuments: (projectId: string, params: PageQuery) => {
    return get<PageResult<Document>>(`/lg/projects/${encodeURIComponent(projectId)}/sources/documents`, params)
  },

  /**
   * 创建文档（上传）
   * @param projectId 项目ID
   * @param data 文档数据
   * @returns 创建的文档信息
   */
  createDocument: (projectId: string, file: File) => {
    return upload<Document>(`/lg/projects/${projectId}/sources/documents/upload`, file)
  },

  /**
   * 删除文档
   * @param projectId 项目ID
   * @param id 文档ID
   * @returns 删除结果
   */
  deleteDocument: (projectId: string, id: string) => {
    return del(`/lg/projects/${projectId}/sources/documents/${id}`)
  }
}
