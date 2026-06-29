// 通用分页结果
export interface PageResult<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
  totalPages: number
}

// 分页查询参数
export interface PageQuery {
  pageNum: number
  pageSize: number
}

// 节点选项
export interface NodeOption {
  value: string
  label: string
}

// 项目
export interface Project {
  id: string
  projectCode: string
  projectName: string
  description: string
  projectType: string
  repoUrl: string
  defaultBranch: string
  owner: string
  status: string
  createdAt: string
  updatedAt: string
}

// 扫描版本
export interface ScanVersion {
  id: string
  projectId: string
  versionNo: string
  branchName: string
  commitId: string
  scanStatus: string
  startedAt: string
  finishedAt: string
  errorMessage: string
}

// 图谱节点
export interface GraphNode {
  id: string
  projectId: string
  versionId: string
  nodeType: string
  nodeKey: string
  nodeName: string
  displayName: string
  description: string
  confidence: number
  status: string
  properties: Record<string, any>
  aliasNames?: string
  evidenceIds?: string
  deleted?: number
  createdAt?: string
  updatedAt?: string
}

// 图谱边
export interface GraphEdge {
  id: string
  projectId: string
  versionId: string
  fromNodeId: string
  toNodeId: string
  edgeType: string
  confidence: number
  status: string
  deleted?: number
  createdAt?: string
  updatedAt?: string
}

// 审核记录
export interface ReviewRecord {
  id: string
  projectId: string
  targetType: string
  targetId: string
  targetName: string
  graphType: string
  confidence: number
  evidenceCount: number
  priority: 'HIGH' | 'MEDIUM' | 'LOW'
  status: string
  comment: string
  reviewedBy: string
  reviewedAt: string
  createdAt: string
}

// 证据
export interface Evidence {
  id: string
  projectId?: string
  evidenceType: string
  sourceName: string
  sourcePath?: string
  summary?: string
  content?: string
  location?: string
  relatedNodeIds?: string
  startLine?: number
  endLine?: number
  createdAt: string
}

// 原子事实
export interface Fact {
  id: string
  projectId: string
  factType: string
  factText: string
  sourceType: string
  sourceId: string
  confidence: number
  status: string
  createdAt: string
}

// 测试用例
export interface TestCase {
  id: string
  projectId: string
  versionId: string
  caseCode: string
  caseName: string
  caseType: 'API' | 'DB_ASSERTION' | 'PERMISSION' | 'E2E'
  targetNodeId: string
  preconditions: string
  steps: string
  expectedResult: string
  status: string
  createdAt: string
  updatedAt?: string
}

// 测试执行
export interface TestRun {
  id: string
  projectId: string
  versionId: string
  environment: string
  status: 'SCHEDULED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  startedAt: string
  finishedAt: string
  totalCases: number
  passedCases: number
  failedCases: number
}

// 测试结果
export interface TestResult {
  id: string
  projectId: string
  versionId: string
  testCaseId: string
  executionId: string
  resultStatus: 'PASSED' | 'FAILED' | 'ERROR' | 'SKIPPED'
  requestData: string
  responseData: string
  errorMessage: string
  durationMs: number
  executedAt: string
}

// 测试断言
export interface TestAssertion {
  id: string
  testCaseId: string
  assertionType: string
  assertionName: string
  expression: string
  expectedValue: string
  status: string
  createdAt: string
  updatedAt: string
}

// 用户
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

// 代码仓库
export interface CodeRepo {
  id: string
  projectId: string
  repoUrl: string
  branchName: string
  localPath: string
  status: string
  createdAt: string
}

// 数据库连接
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

// 文档
export interface Document {
  id: string
  projectId: string
  docName: string
  docType: string
  fileUrl: string
  status: string
  createdAt: string
}

// 向量文档
export interface VectorDocument {
  id: string
  projectId: number
  chunkType: string
  sourceUri: string
  chunkIndex: number
  content: string
  embedding: number[]
  embeddingModel: string
  embeddingDim: number
  createdAt: string
}

// 报告
export interface Report {
  id: string
  projectId: string
  versionId: string
  reportType: string
  reportName: string
  status: string
  generatedAt: string
  completedAt: string
}

// 迁移就绪度报告
export interface MigrationReadinessReport {
  projectId: string
  projectName?: string
  generatedAt: string
  overallScore: number
  architectureUnderstandingScore: number
  businessKnowledgeScore: number
  testCoverageScore: number
  confidenceLevel: number
  totalNodes: number
  confirmedNodes: number
  pendingNodes: number
  totalEdges: number
  confirmedEdges: number
  pendingEdges: number
  nodeTypeStats: NodeTypeStat[]
  riskItems: RiskItem[]
  recommendations: string[]
}

export interface NodeTypeStat {
  nodeType: string
  displayName: string
  total: number
  confirmed: number
  averageConfidence: number
}

export interface RiskItem {
  riskType: string
  description: string
  affectedNodeId: string
  affectedNodeName: string
  riskLevel: number
}

// 置信度趋势报告
export interface ConfidenceTrendReport {
  projectId: string
  versionId: string
  startDate: string
  endDate: string
  dailyData: DailyData[]
  startingAverageConfidence: number
  endingAverageConfidence: number
  totalImprovement: number
  trendDirection: 'UP' | 'FLAT' | 'DOWN'
}

export interface DailyData {
  date: string
  averageConfidence: number
  confirmedNodes: number
  newNodes: number
}

// 测试覆盖率报告
export interface TestCoverageReport {
  projectId: string
  versionId: string
  totalNodes: number
  totalEdges: number
  coveredNodes: number
  coveredEdges: number
  coveragePercentage: number
  edgeCoveragePercentage: number
  highConfidenceUncovered: UncoveredItem[]
}

export interface UncoveredItem {
  nodeId: string
  nodeName: string
  nodeType: string
  confidence: number
}

// 图谱质量报告
export interface GraphQualityReport {
  projectId: string
  versionId: string
  totalNodes: number
  totalEdges: number
  averageConfidence: number
  averageNodeDegree: number
  confidenceDistribution: ConfidenceBin[]
  qualityIssues: QualityIssue[]
  qualityRating: 'A' | 'B' | 'C' | 'D'
}

export interface ConfidenceBin {
  lowerBound: number
  upperBound: number
  nodeCount: number
}

export interface QualityIssue {
  issueType: string
  description: string
  nodeId: string
  nodeName: string
  impact: number
}

// 图谱合并候选
export interface MergeCandidate {
  nodeAId: string
  nodeBId: string
  nameScore: number
  semanticScore: number
  structScore: number
  neighborScore: number
  evidenceScore: number
  totalScore: number
}

// 图谱合并决策
export interface GraphMergeDecision {
  shouldMerge: boolean
  confidence: number
  reasoning: string
}

// 字典
export interface Dictionary {
  id: string
  dictType: string
  dictCode: string
  dictName: string
  dictValue: string
  sort: number
  status: string
}

// 系统配置
export interface SystemConfig {
  id: string
  configKey: string
  configValue: string
  configDesc: string
}

// 枚举定义
export enum GraphNodeType {
  API_ENDPOINT = 'ApiEndpoint',
  DATABASE_TABLE = 'DatabaseTable',
  BUSINESS_OBJECT = 'BusinessObject',
  BUSINESS_PROCESS = 'BusinessProcess',
  BUSINESS_RULE = 'BusinessRule',
  SERVICE = 'Service',
  CONTROLLER = 'Controller',
  REPOSITORY = 'Repository',
  ENTITY = 'Entity',
  SQL_STATEMENT = 'SqlStatement',
}

export enum GraphEdgeType {
  CALLS = 'CALLS',
  CONTAINS = 'CONTAINS',
  DEPENDS_ON = 'DEPENDS_ON',
  ACCESSES = 'ACCESSES',
  MAPPED_TO = 'MAPPED_TO',
  IMPLEMENTS = 'IMPLEMENTS',
  EXTENDS = 'EXTENDS',
}

export enum ReviewStatus {
  PENDING = 'PENDING',
  NEED_REVIEW = 'NEED_REVIEW',
  APPROVED = 'APPROVED',
  REJECTED = 'REJECTED',
  CONFIRMED = 'CONFIRMED',
  IGNORED = 'IGNORED',
}

export enum ReviewPriority {
  HIGH = 'HIGH',
  MEDIUM = 'MEDIUM',
  LOW = 'LOW',
}

// API 响应格式
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId?: string
}
