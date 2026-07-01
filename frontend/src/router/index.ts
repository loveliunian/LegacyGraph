import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { loadAllDicts } from '@/utils/dict'

/**
 * F-H6：路由元信息类型增强。
 * - requiresAuth：是否需要登录（默认 true）。
 * - roles：允许访问的角色列表，留空表示任意已登录用户均可访问。
 */
declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    title?: string
    /** 允许访问的角色，命中其一即可；缺省表示不限角色（F-H6） */
    roles?: string[]
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/LoginPage.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/',
    component: () => import('@/components/AppLayout.vue'),
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/Index.vue'),
        meta: { requiresAuth: true, title: 'menu.dashboard' }
      },
      {
        path: 'projects',
        name: 'Projects',
        component: () => import('@/views/project/ProjectList.vue'),
        meta: { requiresAuth: true, title: 'menu.projects' }
      },
      {
        path: 'projects/:projectId',
        name: 'ProjectDetail',
        component: () => import('@/views/project/ProjectDetail.vue'),
        meta: { requiresAuth: true, title: 'menu.projectDetail' },
        redirect: { name: 'ProjectOverview' },
        children: [
          {
            path: 'overview',
            name: 'ProjectOverview',
            component: () => import('@/views/project/ProjectOverview.vue'),
            meta: { title: 'menu.projectOverview' }
          },
          {
            path: 'repos',
            name: 'CodeRepos',
            component: () => import('@/views/source/CodeRepoList.vue'),
            meta: { title: 'menu.codeRepos' }
          },
          {
            path: 'databases',
            name: 'Databases',
            component: () => import('@/views/source/DatabaseList.vue'),
            meta: { title: 'menu.databases' }
          },
          {
            path: 'documents',
            name: 'Documents',
            component: () => import('@/views/source/DocumentList.vue'),
            meta: { title: 'menu.documents' }
          },
          {
            path: 'scan-versions',
            name: 'ScanVersions',
            component: () => import('@/views/scan/ScanVersionList.vue'),
            meta: { title: 'menu.scanVersions' }
          },
          {
            path: 'scan-versions/create',
            name: 'CreateScan',
            component: () => import('@/views/scan/CreateScan.vue'),
            meta: { title: '新建扫描' }
          },
          {
            path: 'graph/code',
            name: 'CodeGraph',
            component: () => import('@/views/graph/CodeGraph.vue'),
            meta: { title: 'menu.codeGraph' }
          },
          {
            path: 'graph/unified',
            name: 'UnifiedGraph',
            component: () => import('@/views/graph/UnifiedGraph.vue'),
            meta: { title: 'menu.unifiedGraph' }
          },
          {
            path: 'graph/business',
            name: 'BusinessGraph',
            component: () => import('@/views/graph/BusinessGraph.vue'),
            meta: { title: 'menu.businessGraph' }
          },
          {
            path: 'graph/feature',
            name: 'FeatureGraph',
            component: () => import('@/views/graph/FeatureGraph.vue'),
            meta: { title: 'menu.featureGraph' }
          },
          {
            path: 'graph/lineage',
            name: 'DataLineageGraph',
            component: () => import('@/views/graph/DataLineageGraph.vue'),
            meta: { title: 'menu.dataLineage' }
          },
          {
            path: 'graph/runtime',
            name: 'RuntimeGraph',
            component: () => import('@/views/graph/RuntimeGraph.vue'),
            meta: { title: 'menu.runtimeGraph' }
          },
          {
            path: 'reviews',
            name: 'Reviews',
            component: () => import('@/views/review/ReviewList.vue'),
            meta: { title: 'menu.reviews' }
          },
          {
            path: 'review-history',
            name: 'ReviewHistory',
            component: () => import('@/views/review/ReviewHistory.vue'),
            meta: { title: 'menu.reviewHistory' }
          },
          {
            path: 'facts',
            name: 'Facts',
            component: () => import('@/views/fact/FactList.vue'),
            meta: { title: 'menu.facts' }
          },
          {
            path: 'evidence',
            name: 'Evidence',
            component: () => import('@/views/fact/EvidenceSearch.vue'),
            meta: { title: 'menu.evidence' }
          },
          {
            path: 'test-cases',
            name: 'TestCases',
            component: () => import('@/views/test/TestCaseList.vue'),
            meta: { title: 'menu.testCases' }
          },
          {
            path: 'test-cases/new',
            name: 'TestCaseEditorNew',
            component: () => import('@/views/test/TestCaseEditor.vue'),
            meta: { title: 'menu.testCaseEditor' }
          },
          {
            path: 'test-cases/:id/edit',
            name: 'TestCaseEditorEdit',
            component: () => import('@/views/test/TestCaseEditor.vue'),
            meta: { title: 'menu.testCaseEditor' }
          },
          {
            path: 'test-runs',
            name: 'TestRunList',
            component: () => import('@/views/test/TestRunList.vue'),
            meta: { title: 'menu.testRuns' }
          },
          {
            path: 'test-runs/:id',
            name: 'TestRunDetail',
            component: () => import('@/views/test/TestRunDetail.vue'),
            meta: { title: 'menu.testRunDetail' }
          },
          {
            path: 'validation',
            name: 'Validation',
            component: () => import('@/views/report/ValidationReport.vue'),
            meta: { title: 'menu.validationReport' }
          },
          {
            path: 'migration/risks',
            name: 'MigrationRiskList',
            component: () => import('@/views/migration/RiskList.vue'),
            meta: { title: 'menu.migrationRisks' }
          },
          {
            path: 'migration/risks/:riskId',
            name: 'MigrationRiskDetail',
            component: () => import('@/views/migration/RiskDetail.vue'),
            meta: { title: 'menu.riskDetail' }
          },
          {
            path: 'audit/logs',
            name: 'AuditLogList',
            component: () => import('@/views/audit/LogList.vue'),
            meta: { title: 'menu.auditLogs' }
          },
          {
            path: 'audit/logs/:id',
            name: 'AuditLogDetail',
            component: () => import('@/views/audit/LogDetail.vue'),
            meta: { title: 'menu.logDetail' }
          },
          {
            path: 'workbench',
            name: 'EvidenceWorkbench',
            component: () => import('@/views/workbench/EvidenceWorkbench.vue'),
            meta: { title: '证据工作台' }
          },
          {
            path: 'change-tasks',
            name: 'ChangeTaskList',
            component: () => import('@/views/change/ChangeTaskList.vue'),
            meta: { title: '变更任务' }
          },
          {
            path: 'qa',
            name: 'GraphQa',
            component: () => import('@/views/graph/GraphQa.vue'),
            meta: { title: '图谱问答' }
          },
          {
            path: 'agents',
            name: 'AgentHub',
            component: () => import('@/views/agent/AgentHub.vue'),
            meta: { title: 'AI 助手' }
          }
        ]
      },
      {
        path: 'system',
        component: () => import('@/components/SystemSettingsLayout.vue'),
        meta: { requiresAuth: true, roles: ['ADMIN'] },
        redirect: { name: 'SystemDictionaryList' },
        children: [
          {
            path: 'users',
            name: 'SystemUserList',
            component: () => import('@/views/system/UserList.vue'),
            meta: { title: '用户管理' }
          },
          {
            path: 'dictionaries',
            name: 'SystemDictionaryList',
            component: () => import('@/views/system/DictionaryList.vue'),
            meta: { title: '字典管理' }
          },
          {
            path: 'settings',
            name: 'SystemSettings',
            component: () => import('@/views/system/Settings.vue'),
            meta: { title: '系统配置' }
          },
          {
            path: 'llm',
            name: 'LlmProviderSettings',
            component: () => import('@/views/system/LlmProviderSettings.vue'),
            meta: { title: 'LLM 提供商' }
          },
          {
            path: 'prompts',
            name: 'PromptTemplateList',
            component: () => import('@/views/system/PromptList.vue'),
            meta: { title: '提示词管理' }
          }
        ]
      },
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { requiresAuth: false, title: 'common.error' }
  },
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/error/403.vue'),
    meta: { requiresAuth: false, title: 'common.forbidden' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition || { top: 0 }
  }
})

router.beforeEach(async (to, _from, next) => {
  const userStore = useUserStore()
  const requiresAuth = to.meta.requiresAuth !== false

  if (requiresAuth) {
    // F-H6：不仅校验 token 存在，还校验是否过期；过期则清登录态并回登录页。
    if (!userStore.accessToken || userStore.isTokenExpired()) {
      userStore.clearAuth()
      next('/login')
      return
    }
    if (!userStore.userInfo && userStore.accessToken) {
      try {
        await userStore.fetchCurrentUser()
      } catch {
        // fetchCurrentUser 内部已 clearAuth，此处兜底
        next('/login')
        return
      }
      // fetchCurrentUser 失败时内部已清除 token，跳回登录页
      if (!userStore.accessToken) {
        next('/login')
        return
      }
    }
    // 确保字典已加载到内存（页面刷新后缓存丢失，需重新加载）
    loadAllDicts()
    // F-H6：路由级角色守卫——meta.roles 命中其一才放行，否则跳 403。
    const requiredRoles = to.meta.roles
    if (requiredRoles && requiredRoles.length > 0 && !userStore.hasAnyRole(requiredRoles)) {
      next('/403')
      return
    }
  }

  if (to.path === '/login' && userStore.accessToken && !userStore.isTokenExpired()) {
    next('/dashboard')
    return
  }

  next()
})

// F-H11：移除路由级 beforeResolve/afterEach 的全屏 loading。
// 原实现在每次导航都强制 showLoading，与首屏请求的 loading 双重叠加阻塞 UI；
// 现改为按需 loading（请求层 _showLoading），导航不再叠加全局 loading。

export default router
