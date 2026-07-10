import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { usePluginStore } from '@/stores/plugin'
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
            path: 'workbench',
            name: 'EvidenceWorkbench',
            component: () => import('@/views/workbench/EvidenceWorkbench.vue'),
            meta: { title: 'menu.evidenceWorkbench' }
          },
          {
            path: 'change-tasks',
            name: 'ChangeTaskList',
            component: () => import('@/views/change/ChangeTaskList.vue'),
            meta: { title: 'menu.changeTasks' }
          },
          {
            path: 'qa',
            name: 'GraphQa',
            component: () => import('@/views/graph/GraphQa.vue'),
            meta: { title: 'menu.graphQa' }
          },
          {
            path: 'agents',
            name: 'AgentHub',
            component: () => import('@/views/agent/AgentHub.vue'),
            meta: { title: 'menu.agentHub' }
          },
          {
            path: 'understanding',
            name: 'UnderstandingReport',
            component: () => import('@/views/understanding/UnderstandingReportView.vue'),
            meta: { title: 'menu.understanding' }
          },
          {
            path: 'vector-search',
            name: 'VectorSearch',
            component: () => import('@/views/vector/VectorSearch.vue'),
            meta: { title: 'menu.vectorSearch' }
          },
          {
            path: 'pr-workbench',
            name: 'PrWorkbench',
            component: () => import('@/views/change/PrWorkbench.vue'),
            meta: { title: 'menu.prWorkbench' }
          },
          {
            path: 'graph-diff',
            name: 'GraphDiff',
            component: () => import('@/views/graph/GraphDiff.vue'),
            meta: { title: 'menu.graphDiff' }
          },
          {
            path: 'knowledge',
            name: 'KnowledgeWorkbench',
            component: () => import('@/views/workbench/KnowledgeWorkbench.vue'),
            meta: { title: 'menu.knowledgeWorkbench' }
          },
          {
            path: 'agents/history',
            name: 'AgentHistory',
            component: () => import('@/views/agent/AgentHistory.vue'),
            meta: { title: 'menu.agentHistory' }
          },
          {
            path: 'evidence/conflicts',
            name: 'EvidenceConflict',
            component: () => import('@/views/workbench/EvidenceConflict.vue'),
            meta: { title: 'menu.evidenceConflict' }
          },
          {
            path: 'system-overview',
            name: 'SystemOverviewWorkbench',
            component: () => import('@/views/workbench/SystemOverviewWorkbench.vue'),
            meta: { title: 'menu.systemOverview' }
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
            meta: { title: 'menu.users' }
          },
          {
            path: 'dictionaries',
            name: 'SystemDictionaryList',
            component: () => import('@/views/system/DictionaryList.vue'),
            meta: { title: 'menu.dictionaries' }
          },
          {
            path: 'settings',
            name: 'SystemSettings',
            component: () => import('@/views/system/Settings.vue'),
            meta: { title: 'menu.settings' }
          },
          {
            path: 'llm',
            name: 'LlmProviderSettings',
            component: () => import('@/views/system/LlmProviderSettings.vue'),
            meta: { title: 'menu.llmProvider' }
          },
          {
            path: 'prompts',
            name: 'PromptTemplateList',
            component: () => import('@/views/system/PromptList.vue'),
            meta: { title: 'menu.promptTemplates' }
          },
          {
            path: 'plugins',
            name: 'PluginManagement',
            component: () => import('@/views/system/PluginManagement.vue'),
            meta: { title: 'menu.plugins' }
          },
          {
            path: 'audit-logs',
            name: 'AuditLogList',
            component: () => import('@/views/audit/LogList.vue'),
            meta: { title: 'menu.auditLogs' }
          },
          {
            path: 'audit-logs/:id',
            name: 'AuditLogDetail',
            component: () => import('@/views/audit/LogDetail.vue'),
            meta: { title: 'menu.logDetail' }
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

/**
 * 可插拔视图插件 routeName → 路由组件映射表。
 * 新增插件时在此追加组件引用即可，路由和菜单由后端插件描述符驱动。
 */
const pluginRouteComponents: Record<string, () => Promise<any>> = {
  GraphifyJobs: () => import('@/views/graphify/GraphifyJobCenterView.vue'),
  GraphifyDiff: () => import('@/views/graphify/GraphifyDiffView.vue'),
  GraphifyQuality: () => import('@/views/graphify/GraphifyQualityDashboard.vue'),
  GraphifyCrossRepoImpact: () => import('@/views/graphify/CrossRepositoryImpactView.vue'),
}

/**
 * 从后端拉取已启用的 GRAPH_VIEW 插件，动态注册路由到 ProjectDetail 下。
 * 支持整体插件（子功能存于 metadata.menuItems）和单功能插件两种形态。
 */
async function registerPluginRoutes(): Promise<void> {
  const pluginStore = usePluginStore()
  await pluginStore.loadGraphViewPlugins()
  pluginStore.pluginMenuItems.forEach(item => {
    const component = pluginRouteComponents[item.routeName]
    if (!component) return
    if (router.hasRoute(item.routeName)) return
    router.addRoute('ProjectDetail', {
      path: item.menuPath,
      name: item.routeName,
      component,
      meta: { title: item.menuLabel }
    })
  })
}

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
    await loadAllDicts()
    // F-H6：路由级角色守卫——meta.roles 命中其一才放行，否则跳 403。
    const requiredRoles = to.meta.roles
    if (requiredRoles && requiredRoles.length > 0 && !userStore.hasAnyRole(requiredRoles)) {
      next('/403')
      return
    }
    // 动态加载可插拔视图插件路由（首次进入项目页面时）
    const pluginStore = usePluginStore()
    if (!pluginStore.loaded) {
      try {
        await registerPluginRoutes()
        // 路由刚注册，如果当前目标未匹配则重新导航以命中新路由
        if (to.matched.length === 0) {
          next({ ...to, replace: true })
          return
        }
      } catch (e) {
        console.error('Failed to load plugin routes:', e)
      }
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
