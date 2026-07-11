import { post, get } from '@/utils/request'

/**
 * 需求分析领域类型定义
 * 对应后端 Requirement 相关 API：
 *  - POST /lg/projects/{projectId}/requirements/analyze
 *  - POST /lg/projects/{projectId}/requirements
 *  - POST /lg/projects/{projectId}/requirements/{requirementId}/impact
 *  - GET  /lg/projects/{projectId}/impact-viz/requirements/{requirementId}（G-20 影响可视化）
 */

/** 单条结构化需求条目 */
export interface RequirementItem {
  /** 需求编码，如 REQ-001 */
  code: string
  /** 需求文本描述 */
  text: string
  /** 验收标准列表 */
  acceptanceCriteria?: string[]
  /** 约束条件列表 */
  constraints?: string[]
}

/** 需求分析结果（/requirements/analyze 返回） */
export interface RequirementAnalysis {
  /** 需求目标 */
  goal?: string
  /** 结构化条目列表 */
  items: RequirementItem[]
  /** 待确认问题列表 */
  openQuestions: string[]
}

/** 保存后的需求实体（/requirements 返回，含数据库 ID） */
export interface SavedRequirement extends RequirementAnalysis {
  id: string
  projectId: string
  /** 原始需求文本 */
  text?: string
  status?: string
  createdAt?: string
  updatedAt?: string
}

/** 影响分析中受影响的节点摘要 */
export interface ImpactedNode {
  nodeId: string
  nodeName?: string
  nodeType?: string
  filePath?: string
  symbolName?: string
  [k: string]: unknown
}

/** 影响分析中的路径 */
export interface ImpactPath {
  /** 路径上的节点 ID 序列 */
  nodes?: string[]
  /** 路径上的节点名称序列（便于展示） */
  nodeNames?: string[]
  [k: string]: unknown
}

/** 影响分析结果（/requirements/{requirementId}/impact 返回） */
export interface ImpactAnalysis {
  impactedNodes: ImpactedNode[]
  paths: ImpactPath[]
  [k: string]: unknown
}

/**
 * 需求分析 API
 * 封装需求文本分析、需求保存、影响分析能力
 */
export const requirementApi = {
  /**
   * 提交需求文本进行结构化分析
   * @param projectId 项目 ID
   * @param data 包含 text 的请求体
   * @returns 结构化分析结果（goal, items, openQuestions）
   */
  analyze: (projectId: string, data: { text: string }) => {
    return post<RequirementAnalysis>(
      `/lg/projects/${encodeURIComponent(projectId)}/requirements/analyze`,
      data
    )
  },

  /**
   * 保存需求到数据库并构建图谱
   * @param projectId 项目 ID
   * @param data 需求数据（分析结果 + 原始文本）
   * @returns 保存后的需求实体（含 id）
   */
  save: (
    projectId: string,
    data: {
      text?: string
      goal?: string
      items: RequirementItem[]
      openQuestions?: string[]
    }
  ) => {
    return post<SavedRequirement>(
      `/lg/projects/${encodeURIComponent(projectId)}/requirements`,
      data
    )
  },

  /**
   * 查询需求的影响分析
   * @param projectId 项目 ID
   * @param requirementId 需求 ID
   * @returns 影响分析结果（impactedNodes, paths）
   */
  impact: (projectId: string, requirementId: string) => {
    return post<ImpactAnalysis>(
      `/lg/projects/${encodeURIComponent(projectId)}/requirements/${encodeURIComponent(requirementId)}/impact`
    )
  },

  /**
   * 回答需求的开放问题并重新分析
   * @param projectId 项目 ID
   * @param requirementId 需求 ID
   * @param answers key=问题, value=回答
   * @returns 重新分析后的需求响应
   */
  clarify: (projectId: string, requirementId: string, answers: Record<string, string>) => {
    return post<SavedRequirement>(
      `/lg/projects/${encodeURIComponent(projectId)}/requirements/${encodeURIComponent(requirementId)}/clarify`,
      { answers }
    )
  },
}

// ==================== G-20: 需求影响可视化 ====================

/** 可视化节点（对应后端 ImpactVisualizationData.VizNode） */
export interface VizNode {
  /** 节点 ID */
  id: string
  /** 节点显示名称 */
  label: string
  /** 节点类型（如 RequirementItem / Service / Table） */
  type: string
  /** 影响层级 L0~L4，用于着色 */
  impactLevel: string
  /** 风险分数 [0,1]，用于控制节点大小 */
  riskScore: number
  /** 距离变更起点的跳数 */
  depth: number
}

/** 可视化边（对应后端 ImpactVisualizationData.VizEdge） */
export interface VizEdge {
  /** 起点节点 key */
  source: string
  /** 终点节点 key */
  target: string
  /** 关系类型（如 CALLS / READS / DATA_FLOW） */
  relationType: string
}

/** 影响摘要统计（对应后端 ImpactVisualizationData.VizSummary） */
export interface VizSummary {
  /** 受影响节点总数 */
  totalNodes: number
  /** 按影响层级分组的节点数（key=L0/L1/L2/L3/L4） */
  byLevel: Record<string, number>
  /** 当前子图中的最大风险分数 */
  maxRiskScore: number
  /** 高风险节点名称列表 */
  highRiskNodes: string[]
}

/** 影响可视化数据（对应后端 ImpactVisualizationData） */
export interface ImpactVisualizationData {
  nodes: VizNode[]
  edges: VizEdge[]
  summary: VizSummary
}

/**
 * 需求影响可视化 API（G-20）
 * 提供影响子图可视化数据与摘要统计查询
 */
export const impactVizApi = {
  /**
   * 获取需求影响可视化数据（节点 + 边 + 摘要）
   * @param projectId 项目 ID
   * @param requirementId 需求 ID
   * @returns 影响可视化数据
   */
  getVisualization: (projectId: string, requirementId: string) => {
    return get<ImpactVisualizationData>(
      `/lg/projects/${encodeURIComponent(projectId)}/impact-viz/requirements/${encodeURIComponent(requirementId)}`
    )
  },

  /**
   * 获取需求影响摘要（简化统计）
   * @param projectId 项目 ID
   * @param requirementId 需求 ID
   * @returns 影响摘要
   */
  getSummary: (projectId: string, requirementId: string) => {
    return get<VizSummary>(
      `/lg/projects/${encodeURIComponent(projectId)}/impact-viz/requirements/${encodeURIComponent(requirementId)}/summary`
    )
  },
}
