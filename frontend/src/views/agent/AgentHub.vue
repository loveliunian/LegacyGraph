<template>
  <div class="agent-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <el-icon><Cpu /></el-icon>
          <span style="margin-left: 8px; font-weight: 600;">AI 助手</span>
          <el-tag size="small" type="primary" style="margin-left: 12px;">{{ agentCount }} 项能力</el-tag>
        </div>
      </template>

      <el-row :gutter="16">
        <el-col
          v-for="agent in agents"
          :key="agent.type"
          :span="8"
          style="margin-bottom: 16px;"
        >
          <el-card
            class="agent-card"
            shadow="hover"
            @click="openAgent(agent)"
          >
            <div class="agent-icon">
              <el-icon :size="28"><component :is="agent.icon" /></el-icon>
            </div>
            <h4>{{ agent.title }}</h4>
            <p class="agent-desc">{{ agent.desc }}</p>
            <el-tag size="small" :type="agent.tagType">{{ agent.tag }}</el-tag>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <!-- Agent 执行对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="activeAgent?.title"
      width="700px"
      destroy-on-close
    >
      <template v-if="activeAgent">
        <!-- 通用输入 -->
        <el-form v-if="activeAgent.type === 'general'" label-width="80px">
          <el-form-item label="Agent 类型">
            <el-select v-model="generalForm.agentType" placeholder="选择 Agent" style="width: 100%">
              <el-option label="代码事实抽取" value="codefact" />
              <el-option label="文档理解" value="docunderstanding" />
              <el-option label="功能映射" value="featuremapping" />
              <el-option label="测试用例生成" value="testcasegeneration" />
            </el-select>
          </el-form-item>
          <el-form-item label="参数 (JSON)">
            <el-input
              v-model="generalForm.variables"
              type="textarea"
              :rows="6"
              placeholder='{"codeContent": "...", "sourcePath": "..."}'
            />
          </el-form-item>
        </el-form>

        <!-- SQL 分析 -->
        <el-form v-if="activeAgent.type === 'sql'" label-width="100px">
          <el-form-item label="SQL 标识">
            <el-input v-model="sqlForm.sqlKey" placeholder="SQL 语句的标识/名称" />
          </el-form-item>
          <el-form-item label="SQL 语句" required>
            <el-input v-model="sqlForm.sql" type="textarea" :rows="6" placeholder="SELECT ... FROM ..." />
          </el-form-item>
          <el-form-item label="表结构信息">
            <el-input v-model="sqlForm.schemaInfo" type="textarea" :rows="3" placeholder="可选的表结构信息 (CREATE TABLE ...)" />
          </el-form-item>
        </el-form>

        <!-- 测试生成 -->
        <el-form v-if="activeAgent.type === 'test'" label-width="100px">
          <el-form-item label="功能 Key" required>
            <el-input v-model="testForm.featureKey" placeholder="如: user-login" />
          </el-form-item>
          <el-form-item label="功能名称">
            <el-input v-model="testForm.featureName" placeholder="如: 用户登录" />
          </el-form-item>
          <el-form-item label="API 端点">
            <el-input v-model="testForm.apiEndpoint" placeholder="如: POST /api/login" />
          </el-form-item>
          <el-form-item label="关联表">
            <el-input v-model="testForm.relatedTables" placeholder="如: user,login_log" />
          </el-form-item>
          <el-form-item label="业务规则">
            <el-input v-model="testForm.businessRules" type="textarea" :rows="3" placeholder="如: 密码错误3次锁定" />
          </el-form-item>
        </el-form>

        <!-- 审核建议 -->
        <el-form v-if="activeAgent.type === 'review'" label-width="100px">
          <el-form-item label="目标 ID" required>
            <el-input v-model="reviewForm.targetId" placeholder="待审核的目标节点ID" />
          </el-form-item>
          <el-form-item label="目标类型">
            <el-input v-model="reviewForm.targetType" placeholder="如: GraphNode" />
          </el-form-item>
          <el-form-item label="目标内容">
            <el-input v-model="reviewForm.content" type="textarea" :rows="6" placeholder="待审核的内容" />
          </el-form-item>
        </el-form>

        <!-- 测试失败分析 -->
        <el-form v-if="activeAgent.type === 'failure'" label-width="100px">
          <el-form-item label="失败用例" required>
            <el-input v-model="failureForm.testCaseName" placeholder="测试用例名称" />
          </el-form-item>
          <el-form-item label="错误信息" required>
            <el-input v-model="failureForm.errorMessage" type="textarea" :rows="3" placeholder="错误堆栈或失败消息" />
          </el-form-item>
          <el-form-item label="环境信息">
            <el-input v-model="failureForm.environment" placeholder="如: dev/test/prod" />
          </el-form-item>
          <el-form-item label="最近变更">
            <el-input v-model="failureForm.recentChanges" type="textarea" :rows="3" placeholder="最近代码变更说明" />
          </el-form-item>
        </el-form>

        <!-- 报告洞察 -->
        <el-form v-if="activeAgent.type === 'report'" label-width="100px">
          <el-form-item label="指标数据" required>
            <el-input v-model="reportForm.metrics" type="textarea" :rows="6" placeholder="图谱指标 JSON 或描述" />
          </el-form-item>
          <el-form-item label="缺口信息">
            <el-input v-model="reportForm.gaps" type="textarea" :rows="3" placeholder="数据缺口描述" />
          </el-form-item>
        </el-form>

        <!-- 重构建议 -->
        <el-form v-if="activeAgent.type === 'refactor'" label-width="100px">
          <el-form-item label="目标文件" required>
            <el-input v-model="refactorForm.target" placeholder="如: src/service/UserService.java" />
          </el-form-item>
          <el-form-item label="异味类型">
            <el-select v-model="refactorForm.smellType" placeholder="选择" style="width: 100%">
              <el-option label="上帝类 (God Class)" value="god_class" />
              <el-option label="长方法 (Long Method)" value="long_method" />
              <el-option label="重复代码" value="duplication" />
              <el-option label="特性依恋" value="feature_envy" />
              <el-option label="数据泥团" value="data_clumps" />
            </el-select>
          </el-form-item>
          <el-form-item label="代码片段">
            <el-input v-model="refactorForm.code" type="textarea" :rows="8" placeholder="粘贴需要分析的代码" />
          </el-form-item>
        </el-form>

        <!-- 变更影响 -->
        <el-form v-if="activeAgent.type === 'change'" label-width="100px">
          <el-form-item label="变更目标" required>
            <el-input v-model="changeForm.changeTarget" placeholder="如: UserService.createUser" />
          </el-form-item>
          <el-form-item label="变更描述" required>
            <el-input v-model="changeForm.changeDescription" type="textarea" :rows="3" placeholder="描述你要做什么变更" />
          </el-form-item>
          <el-form-item label="依赖列表">
            <el-input v-model="changeForm.dependencies" type="textarea" :rows="3" placeholder="相关依赖（每行一个）" />
          </el-form-item>
        </el-form>

        <!-- 迁移转换 -->
        <el-form v-if="activeAgent.type === 'migration'" label-width="100px">
          <el-form-item label="迁移方向" required>
            <el-select v-model="migrationForm.migrationDirection" style="width: 100%">
              <el-option label="Struts → Spring MVC" value="struts2spring" />
              <el-option label="MyBatis → JPA" value="mybatis2jpa" />
              <el-option label="XML → 注解" value="xml2annotation" />
              <el-option label="Java 8 → Java 17" value="java8to17" />
            </el-select>
          </el-form-item>
          <el-form-item label="源文件路径">
            <el-input v-model="migrationForm.sourcePath" placeholder="如: src/main/java/..." />
          </el-form-item>
          <el-form-item label="代码" required>
            <el-input v-model="migrationForm.code" type="textarea" :rows="8" placeholder="粘贴需要转换的代码" />
          </el-form-item>
        </el-form>

        <!-- PR 描述 -->
        <el-form v-if="activeAgent.type === 'pr'" label-width="100px">
          <el-form-item label="分支名">
            <el-input v-model="prForm.branch" placeholder="如: feature/user-login" />
          </el-form-item>
          <el-form-item label="关联 Issue">
            <el-input v-model="prForm.issue" placeholder="如: #42 用户登录功能" />
          </el-form-item>
          <el-form-item label="Diff 内容" required>
            <el-input v-model="prForm.diff" type="textarea" :rows="10" placeholder="git diff 输出" />
          </el-form-item>
        </el-form>
      </template>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="executeAgent" :loading="executing">
          <el-icon><Promotion /></el-icon>
          执行
        </el-button>
      </template>
    </el-dialog>

    <!-- 执行结果对话框 -->
    <el-dialog v-model="resultVisible" title="执行结果" width="700px" destroy-on-close>
      <div class="result-content">
        <pre>{{ resultText }}</pre>
      </div>
      <template #footer>
        <el-button @click="resultVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Cpu, Promotion, MagicStick, Document, Connection,
  Search, TrendCharts, RefreshRight, WarningFilled,
  Switch, EditPen
} from '@element-plus/icons-vue'
import { agentApi } from '@/api'

const route = useRoute()
const projectId = route.params.projectId as string

const agents = [
  { type: 'general', title: '通用 Agent', desc: '代码事实抽取、文档理解、功能映射、测试生成', icon: MagicStick, tag: '基础', tagType: 'primary' as const },
  { type: 'sql', title: 'SQL 分析', desc: '分析 SQL 性能问题，给出优化建议与改写后的 SQL', icon: Search, tag: '数据库', tagType: 'warning' as const },
  { type: 'test', title: '测试生成', desc: '根据功能描述自动生成测试用例', icon: Document, tag: '测试', tagType: 'success' as const },
  { type: 'review', title: '审核建议', desc: '对图谱待审核项生成 LLM 审核建议', icon: TrendCharts, tag: '审核', tagType: 'info' as const },
  { type: 'failure', title: '失败分析', desc: '测试失败根因分析、排查步骤与复测建议', icon: WarningFilled, tag: '测试', tagType: 'danger' as const },
  { type: 'report', title: '报告洞察', desc: '根据图谱指标生成行动建议', icon: TrendCharts, tag: '报告', tagType: 'success' as const },
  { type: 'refactor', title: '重构建议', desc: '分析职责边界，给出拆分建议与重构骨架', icon: RefreshRight, tag: '代码', tagType: 'warning' as const },
  { type: 'change', title: '变更影响', desc: '语义级判断变更类型、严重程度与回归范围', icon: Connection, tag: '分析', tagType: 'danger' as const },
  { type: 'migration', title: '迁移转换', desc: '按迁移规则给出转换建议与转换后代码', icon: Switch, tag: '迁移', tagType: 'primary' as const },
  { type: 'pr', title: 'PR 描述', desc: '按 Conventional Commits 生成提交信息与 PR 描述', icon: EditPen, tag: '协作', tagType: 'info' as const },
]

const agentCount = agents.length

const dialogVisible = ref(false)
const resultVisible = ref(false)
const executing = ref(false)
const resultText = ref('')
const activeAgent = ref<any>(null)

// 各 Agent 表单数据
const generalForm = reactive({ agentType: 'codefact', variables: '' })
const sqlForm = reactive({ sqlKey: '', sql: '', schemaInfo: '' })
const testForm = reactive({ featureKey: '', featureName: '', apiEndpoint: '', relatedTables: '', businessRules: '' })
const reviewForm = reactive({ targetId: '', targetType: '', content: '' })
const failureForm = reactive({ testCaseName: '', errorMessage: '', environment: '', recentChanges: '' })
const reportForm = reactive({ metrics: '', gaps: '' })
const refactorForm = reactive({ target: '', smellType: '', code: '' })
const changeForm = reactive({ changeTarget: '', changeDescription: '', dependencies: '' })
const migrationForm = reactive({ migrationDirection: '', sourcePath: '', code: '' })
const prForm = reactive({ branch: '', issue: '', diff: '' })

const openAgent = (agent: any) => {
  activeAgent.value = agent
  dialogVisible.value = true
}

const executeAgent = async () => {
  executing.value = true
  try {
    let res: any
    const type = activeAgent.value?.type
    switch (type) {
      case 'general': {
        let vars: Record<string, string> = {}
        try { vars = JSON.parse(generalForm.variables || '{}') } catch { /* use empty */ }
        res = await agentApi.run({ agentType: generalForm.agentType, projectId, params: vars })
        break
      }
      case 'sql':
        res = await agentApi.analyzeSql({ projectId, sqlKey: sqlForm.sqlKey, sql: sqlForm.sql, schemaInfo: sqlForm.schemaInfo })
        break
      case 'test':
        res = await agentApi.generateTests({
          projectId, featureKey: testForm.featureKey, featureName: testForm.featureName,
          apiEndpoint: testForm.apiEndpoint, relatedTables: testForm.relatedTables,
          businessRules: testForm.businessRules, httpMethod: 'GET',
        })
        break
      case 'review':
        res = await agentApi.reviewSuggest({ projectId, targetId: reviewForm.targetId, targetType: reviewForm.targetType, content: reviewForm.content })
        break
      case 'failure':
        res = await agentApi.analyzeTestFailure({
          projectId, testCaseName: failureForm.testCaseName, errorMessage: failureForm.errorMessage,
          environment: failureForm.environment, recentChanges: failureForm.recentChanges,
        })
        break
      case 'report':
        res = await agentApi.reportInsights({ projectId, metrics: reportForm.metrics, gaps: reportForm.gaps })
        break
      case 'refactor':
        res = await agentApi.refactorSuggest({ projectId, target: refactorForm.target, smellType: refactorForm.smellType, code: refactorForm.code })
        break
      case 'change':
        res = await agentApi.changeImpact({
          projectId, changeTarget: changeForm.changeTarget, changeDescription: changeForm.changeDescription, dependencies: changeForm.dependencies,
        })
        break
      case 'migration':
        res = await agentApi.migrationConvert({
          projectId, migrationDirection: migrationForm.migrationDirection, sourcePath: migrationForm.sourcePath, code: migrationForm.code,
        })
        break
      case 'pr':
        res = await agentApi.prDescribe({ projectId, branch: prForm.branch, issue: prForm.issue, diff: prForm.diff })
        break
    }

    resultText.value = JSON.stringify(res?.data || res, null, 2)
    dialogVisible.value = false
    resultVisible.value = true
    ElMessage.success('执行完成')
  } catch {
    ElMessage.error('执行失败')
  } finally {
    executing.value = false
  }
}
</script>

<style scoped>
.agent-page {
  padding: 0;
}

.card-header {
  display: flex;
  align-items: center;
}

.agent-card {
  cursor: pointer;
  transition: all 0.2s;
  text-align: center;
  padding: 10px 0;
}

.agent-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
}

.agent-icon {
  color: #409eff;
  margin-bottom: 8px;
}

.agent-card h4 {
  margin: 8px 0;
  font-size: 15px;
  color: #303133;
}

.agent-desc {
  font-size: 12px;
  color: #909399;
  margin: 6px 0 10px;
  line-height: 1.4;
  min-height: 32px;
}

.result-content {
  max-height: 500px;
  overflow: auto;
}

.result-content pre {
  background: #f5f7fa;
  padding: 16px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
