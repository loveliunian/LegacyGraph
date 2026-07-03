<template>
  <div class="agent-page">
    <!-- 使用指引 -->
    <el-collapse
      v-model="helpOpen"
      class="help-collapse">
      <el-collapse-item name="help">
        <template #title>
          <el-icon><QuestionFilled /></el-icon>
          <span style="margin-left: 8px; font-weight: 600;">不知道怎么用？点击查看使用指引</span>
        </template>
        <div class="help-content">
          <p><strong>AI 助手提供 {{ AGENT_COUNT }} 项智能能力</strong>，覆盖代码理解、测试生成、质量审查、重构迁移等场景。</p>
          <el-divider />
          <p><strong>三步上手：</strong></p>
          <ol>
            <li><strong>选能力</strong> — 点击下方卡片，选一个你要用的能力</li>
            <li><strong>填信息</strong> — 按表单提示填写，每个字段都有示例，也可点「填入示例」快速体验</li>
            <li><strong>看结果</strong> — 执行后看到结构化的分析结果，不再是看不懂的 JSON</li>
          </ol>
          <el-divider />
          <p><strong>常用场景速查：</strong></p>
          <el-table
            :data="QUICK_REF"
            size="small"
            stripe>
            <el-table-column
              prop="want"
              label="我想..."
              width="220" />
            <el-table-column
              prop="use"
              label="用这个能力"
              width="160" />
            <el-table-column
              prop="need"
              label="需要准备" />
          </el-table>
        </div>
      </el-collapse-item>
    </el-collapse>

    <!-- 能力卡片 -->
    <el-card style="margin-top: 16px;">
      <template #header>
        <div class="card-header">
          <el-icon><Cpu /></el-icon>
          <span style="margin-left: 8px; font-weight: 600;">AI 助手</span>
          <el-tag
            size="small"
            type="primary"
            style="margin-left: 12px;">
            {{ AGENT_COUNT }} 项能力
          </el-tag>
        </div>
      </template>

      <el-row :gutter="16">
        <el-col
          v-for="agent in AGENTS"
          :key="agent.type"
          :xs="24"
          :sm="12"
          :md="8"
          style="margin-bottom: 16px;"
        >
          <el-card
            class="agent-card"
            shadow="hover"
            @click="openAgent(agent)"
          >
            <div class="agent-card-top">
              <div class="agent-icon">
                <el-icon :size="28"><component :is="agent.icon" /></el-icon>
              </div>
              <div class="agent-title-row">
                <h4>{{ agent.title }}</h4>
                <el-tag
                  size="small"
                  :type="agent.tagType">
                  {{ agent.tag }}
                </el-tag>
              </div>
            </div>
            <p class="agent-desc">{{ agent.desc }}</p>
            <div class="agent-extra">
              <div class="agent-scenario">
                <el-icon :size="14"><Aim /></el-icon>
                <span>{{ agent.scene }}</span>
              </div>
              <div class="agent-input-hint">
                <el-icon :size="14"><EditPen /></el-icon>
                <span>{{ agent.inputHint }}</span>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <!-- Agent 执行对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="activeAgent?.title"
      width="680px"
      destroy-on-close
      top="5vh"
    >
      <el-alert
        v-if="activeAgent"
        :title="activeAgent.scene"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 16px;"
      />

      <!-- ===== 通用 Agent ===== -->
      <template v-if="activeAgent?.type === 'general'">
        <el-form label-width="90px">
          <el-form-item label="Agent 类型">
            <el-select
              v-model="generalForm.agentType"
              style="width: 100%">
              <el-option
                v-for="opt in GENERAL_AGENT_TYPES"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <template #label>
              参数
              <el-tooltip
                content="根据上面选的 Agent 类型填写对应参数，字段名见下方提示"
                placement="top">
                <el-icon style="margin-left: 4px; cursor: help;"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-input
              v-model="generalForm.variables"
              type="textarea"
              :rows="6"
              :placeholder="generalPlaceholder"
            />
            <el-button
              link
              type="primary"
              size="small"
              style="margin-top: 4px;"
              @click="fillGeneralExample">
              填入示例
            </el-button>
          </el-form-item>
        </el-form>
      </template>

      <!-- ===== SQL 分析 ===== -->
      <template v-if="activeAgent?.type === 'sql'">
        <el-form label-width="100px">
          <el-form-item label="SQL 名称">
            <el-input
              v-model="sqlForm.sqlKey"
              placeholder="例：用户订单查询 / order_list_query" />
          </el-form-item>
          <el-form-item>
            <template #label>
              SQL 语句 *
              <el-tooltip
                content="粘贴你要分析的完整 SQL 语句"
                placement="top">
                <el-icon style="margin-left: 4px; cursor: help;"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-input
              v-model="sqlForm.sql"
              type="textarea"
              :rows="5"
              placeholder="SELECT o.*, u.name FROM orders o JOIN users u ON o.user_id = u.id WHERE o.status = 'pending' ORDER BY o.created_at DESC" />
          </el-form-item>
          <el-form-item>
            <template #label>
              表结构
              <el-tooltip
                content="可选，粘贴 CREATE TABLE 语句帮助 AI 更准确分析"
                placement="top">
                <el-icon style="margin-left: 4px; cursor: help;"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-input
              v-model="sqlForm.schemaInfo"
              type="textarea"
              :rows="3"
              placeholder="CREATE TABLE orders (id BIGINT PRIMARY KEY, user_id BIGINT, status VARCHAR(20), created_at TIMESTAMP);&#10;CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(100));" />
          </el-form-item>
          <el-button
            link
            type="primary"
            size="small"
            @click="fillSqlExample">
            填入示例
          </el-button>
        </el-form>
      </template>

      <!-- ===== 测试生成 ===== -->
      <template v-if="activeAgent?.type === 'test'">
        <el-form label-width="100px">
          <el-form-item label="功能标识 *">
            <el-input
              v-model="testForm.featureKey"
              placeholder="例：user-login / order-create" />
          </el-form-item>
          <el-form-item label="功能名称">
            <el-input
              v-model="testForm.featureName"
              placeholder="例：用户登录" />
          </el-form-item>
          <el-form-item label="API 端点">
            <el-input
              v-model="testForm.apiEndpoint"
              placeholder="例：POST /api/login" />
          </el-form-item>
          <el-form-item label="关联表">
            <el-input
              v-model="testForm.relatedTables"
              placeholder="例：user, login_log" />
          </el-form-item>
          <el-form-item label="业务规则">
            <el-input
              v-model="testForm.businessRules"
              type="textarea"
              :rows="3"
              placeholder="例：密码错误3次锁定账户30分钟；登录成功后记录登录日志" />
          </el-form-item>
          <el-button
            link
            type="primary"
            size="small"
            @click="fillTestExample">
            填入示例
          </el-button>
        </el-form>
      </template>

      <!-- ===== 审核建议 ===== -->
      <template v-if="activeAgent?.type === 'review'">
        <el-form label-width="100px">
          <el-form-item label="目标 ID *">
            <el-input
              v-model="reviewForm.targetId"
              placeholder="例：node-abc123 或 claim-xyz" />
          </el-form-item>
          <el-form-item label="目标类型">
            <el-input
              v-model="reviewForm.targetType"
              placeholder="例：GraphNode / KnowledgeClaim" />
          </el-form-item>
          <el-form-item label="待审内容">
            <el-input
              v-model="reviewForm.content"
              type="textarea"
              :rows="5"
              placeholder="粘贴需要 AI 审核的内容，比如一段代码/一个图谱节点属性/一条知识主张" />
          </el-form-item>
          <el-button
            link
            type="primary"
            size="small"
            @click="fillReviewExample">
            填入示例
          </el-button>
        </el-form>
      </template>

      <!-- ===== 失败分析 ===== -->
      <template v-if="activeAgent?.type === 'failure'">
        <el-form label-width="100px">
          <el-form-item label="失败用例 *">
            <el-input
              v-model="failureForm.testCaseName"
              placeholder="例：UserLoginTest.testLoginWithInvalidPassword" />
          </el-form-item>
          <el-form-item label="错误信息 *">
            <el-input
              v-model="failureForm.errorMessage"
              type="textarea"
              :rows="3"
              placeholder="粘贴错误堆栈或失败消息" />
          </el-form-item>
          <el-form-item label="运行环境">
            <el-input
              v-model="failureForm.environment"
              placeholder="例：dev / test / CI (GitHub Actions)" />
          </el-form-item>
          <el-form-item label="最近变更">
            <el-input
              v-model="failureForm.recentChanges"
              type="textarea"
              :rows="2"
              placeholder="例：修改了 UserService 的密码加密逻辑" />
          </el-form-item>
          <el-button
            link
            type="primary"
            size="small"
            @click="fillFailureExample">
            填入示例
          </el-button>
        </el-form>
      </template>

      <!-- ===== 报告洞察 ===== -->
      <template v-if="activeAgent?.type === 'report'">
        <el-form label-width="100px">
          <el-form-item>
            <template #label>
              指标数据 *
              <el-tooltip
                content="粘贴图谱扫描报告中的指标数据，如节点数/边数/覆盖率等"
                placement="top">
                <el-icon style="margin-left: 4px; cursor: help;"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-input
              v-model="reportForm.metrics"
              type="textarea"
              :rows="5"
              :placeholder="`{&quot;totalNodes&quot;: 1520, &quot;apiCoverage&quot;: 0.65, &quot;testCoverage&quot;: 0.32, &quot;pendingReviews&quot;: 45}`" />
          </el-form-item>
          <el-form-item label="缺口信息">
            <el-input
              v-model="reportForm.gaps"
              type="textarea"
              :rows="2"
              placeholder="例：缺少订单模块的接口文档、支付模块无测试覆盖" />
          </el-form-item>
          <el-button
            link
            type="primary"
            size="small"
            @click="fillReportExample">
            填入示例
          </el-button>
        </el-form>
      </template>

      <!-- ===== 重构建议 ===== -->
      <template v-if="activeAgent?.type === 'refactor'">
        <el-form label-width="100px">
          <el-form-item label="目标文件 *">
            <el-input
              v-model="refactorForm.target"
              placeholder="例：src/service/UserService.java" />
          </el-form-item>
          <el-form-item label="异味类型">
            <el-select
              v-model="refactorForm.smellType"
              style="width: 100%"
              placeholder="选择代码异味类型">
              <el-option
                v-for="opt in REFACTOR_SMELL_OPTIONS"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="代码片段">
            <el-input
              v-model="refactorForm.code"
              type="textarea"
              :rows="6"
              placeholder="粘贴需要分析的代码（可选，不填则分析整个文件）" />
          </el-form-item>
          <el-button
            link
            type="primary"
            size="small"
            @click="fillRefactorExample">
            填入示例
          </el-button>
        </el-form>
      </template>

      <!-- ===== 变更影响 ===== -->
      <template v-if="activeAgent?.type === 'change'">
        <el-form label-width="100px">
          <el-form-item label="变更目标 *">
            <el-input
              v-model="changeForm.changeTarget"
              placeholder="例：UserService.createUser 方法" />
          </el-form-item>
          <el-form-item label="变更描述 *">
            <el-input
              v-model="changeForm.changeDescription"
              type="textarea"
              :rows="3"
              placeholder="例：给 createUser 方法增加手机号校验逻辑，新增 phone 字段" />
          </el-form-item>
          <el-form-item label="相关依赖">
            <el-input
              v-model="changeForm.dependencies"
              type="textarea"
              :rows="2"
              placeholder="例：UserController&#10;UserMapper&#10;UserValidator" />
          </el-form-item>
          <el-button
            link
            type="primary"
            size="small"
            @click="fillChangeExample">
            填入示例
          </el-button>
        </el-form>
      </template>

      <!-- ===== 迁移转换 ===== -->
      <template v-if="activeAgent?.type === 'migration'">
        <el-form label-width="100px">
          <el-form-item label="迁移方向 *">
            <el-select
              v-model="migrationForm.migrationDirection"
              style="width: 100%"
              placeholder="选择迁移方向">
              <el-option
                v-for="opt in MIGRATION_DIRECTION_OPTIONS"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="源文件路径">
            <el-input
              v-model="migrationForm.sourcePath"
              placeholder="例：src/main/java/com/example/dao/UserDao.xml" />
          </el-form-item>
          <el-form-item>
            <template #label>
              代码 *
              <el-tooltip
                content="粘贴需要转换的源代码"
                placement="top">
                <el-icon style="margin-left: 4px; cursor: help;"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-input
              v-model="migrationForm.code"
              type="textarea"
              :rows="6"
              placeholder="粘贴需要转换的代码" />
          </el-form-item>
          <el-button
            link
            type="primary"
            size="small"
            @click="fillMigrationExample">
            填入示例
          </el-button>
        </el-form>
      </template>

      <!-- ===== PR 描述 ===== -->
      <template v-if="activeAgent?.type === 'pr'">
        <el-form label-width="100px">
          <el-form-item label="分支名">
            <el-input
              v-model="prForm.branch"
              placeholder="例：feature/user-login" />
          </el-form-item>
          <el-form-item label="关联 Issue">
            <el-input
              v-model="prForm.issue"
              placeholder="例：#42 实现用户登录功能" />
          </el-form-item>
          <el-form-item>
            <template #label>
              Diff *
              <el-tooltip
                content="在终端执行 git diff 然后粘贴输出；或粘贴变更文件列表和改动说明"
                placement="top">
                <el-icon style="margin-left: 4px; cursor: help;"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-input
              v-model="prForm.diff"
              type="textarea"
              :rows="8"
              placeholder="粘贴 git diff 输出，或手动描述：&#10;+ 新增 UserController.java&#10;~ 修改 UserService.java - 增加 login 方法&#10;- 删除 LegacyAuthFilter.java" />
          </el-form-item>
          <el-button
            link
            type="primary"
            size="small"
            @click="fillPrExample">
            填入示例
          </el-button>
        </el-form>
      </template>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="executing"
          @click="executeAgent">
          <el-icon><Promotion /></el-icon>
          开始分析
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== 执行结果对话框 ===== -->
    <el-dialog
      v-model="resultVisible"
      :title="resultTitle"
      width="720px"
      destroy-on-close
      top="5vh">
      <div
        v-loading="executing"
        class="result-content">
        <!-- SQL 分析结果 -->
        <template v-if="resultType === 'sql' && resultData">
          <el-alert
            :title="'整体风险：' + (resultData.overallRisk || '未知')"
            :type="riskType(resultData.overallRisk)"
            show-icon
            :closable="false"
            style="margin-bottom: 12px;" />
          <p
            v-if="resultData.summary"
            class="result-summary">
            {{ resultData.summary }}
          </p>

          <h4 v-if="resultData.issues?.length">发现 {{ resultData.issues.length }} 个问题</h4>
          <div
            v-for="(issue, i) in resultData.issues"
            :key="i"
            class="result-issue">
            <div class="issue-header">
              <el-tag
                :type="severityType(issue.severity)"
                size="small">
                {{ issue.severity }}
              </el-tag>
              <el-tag
                type="info"
                size="small"
                style="margin-left: 8px;">
                {{ issue.issueType }}
              </el-tag>
            </div>
            <p><strong>问题：</strong>{{ issue.description }}</p>
            <p><strong>建议：</strong>{{ issue.suggestion }}</p>
          </div>

          <div
            v-if="resultData.optimizedSql"
            style="margin-top: 16px;">
            <h4>优化后的 SQL</h4>
            <pre class="code-block"><code>{{ resultData.optimizedSql }}</code></pre>
          </div>
        </template>

        <!-- 测试生成结果 -->
        <template v-if="resultType === 'test' && resultData">
          <p
            v-if="resultData.summary"
            class="result-summary">
            {{ resultData.summary }}
          </p>
          <el-table
            v-if="resultData.testCases?.length"
            :data="resultData.testCases"
            size="small"
            stripe>
            <el-table-column
              prop="testName"
              label="用例名称"
              width="200" />
            <el-table-column
              label="优先级"
              width="80">
              <template #default="{ row }">
                <el-tag
                  :type="priorityType(row.priority)"
                  size="small">
                  {{ row.priority }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="description"
              label="测试描述" />
          </el-table>
        </template>

        <!-- 审核建议结果 -->
        <template v-if="resultType === 'review' && resultData">
          <el-descriptions
            :column="1"
            border
            size="small">
            <el-descriptions-item label="评分">{{ resultData.score }} / 100</el-descriptions-item>
            <el-descriptions-item label="建议">{{ resultData.suggestion }}</el-descriptions-item>
            <el-descriptions-item
              v-if="resultData.reason"
              label="理由">
              {{ resultData.reason }}
            </el-descriptions-item>
          </el-descriptions>
        </template>

        <!-- 失败分析结果 -->
        <template v-if="resultType === 'failure' && resultData">
          <p
            v-if="resultData.summary"
            class="result-summary">
            <strong>根因摘要：</strong>{{ resultData.summary }}
          </p>

          <h4 v-if="resultData.rootCauses?.length">可能原因</h4>
          <div
            v-for="(rc, i) in resultData.rootCauses"
            :key="i"
            class="result-issue">
            <el-tag
              :type="likelihoodType(rc.likelihood)"
              size="small">
              {{ rc.likelihood }}
            </el-tag>
            <p><strong>原因：</strong>{{ rc.cause }}</p>
            <p v-if="rc.evidence"><strong>证据：</strong>{{ rc.evidence }}</p>
          </div>

          <h4 v-if="resultData.troubleshootingSteps?.length">排查步骤</h4>
          <ol>
            <li
              v-for="(step, i) in resultData.troubleshootingSteps"
              :key="i">
              {{ step }}
            </li>
          </ol>
        </template>

        <!-- 报告洞察结果 -->
        <template v-if="resultType === 'report' && resultData">
          <p
            v-if="resultData.summary"
            class="result-summary">
            {{ resultData.summary }}
          </p>
          <el-table
            v-if="resultData.actions?.length"
            :data="resultData.actions"
            size="small"
            stripe>
            <el-table-column
              label="优先级"
              width="80">
              <template #default="{ row }">
                <el-tag
                  :type="priorityType(row.priority)"
                  size="small">
                  {{ row.priority }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="title"
              label="行动项"
              width="180" />
            <el-table-column
              prop="rationale"
              label="理由" />
          </el-table>
        </template>

        <!-- 重构建议结果 -->
        <template v-if="resultType === 'refactor' && resultData">
          <p
            v-if="resultData.summary"
            class="result-summary">
            {{ resultData.summary }}
          </p>

          <h4 v-if="resultData.splitSuggestions?.length">拆分建议</h4>
          <div
            v-for="(ss, i) in resultData.splitSuggestions"
            :key="i"
            class="result-issue">
            <p><strong>新建单元：</strong>{{ ss.newUnit }}</p>
            <p><strong>职责：</strong>{{ ss.responsibility }}</p>
            <p v-if="ss.movedMethods?.length"><strong>移入方法：</strong>{{ ss.movedMethods.join('、') }}</p>
          </div>

          <div
            v-if="resultData.refactoredSkeleton"
            style="margin-top: 12px;">
            <h4>重构骨架</h4>
            <pre class="code-block"><code>{{ resultData.refactoredSkeleton }}</code></pre>
          </div>
        </template>

        <!-- 变更影响结果 -->
        <template v-if="resultType === 'change' && resultData">
          <el-descriptions
            :column="2"
            border
            size="small">
            <el-descriptions-item label="变更类型">{{ resultData.changeType }}</el-descriptions-item>
            <el-descriptions-item label="严重程度">
              <el-tag
                :type="severityType(resultData.severity)"
                size="small">
                {{ resultData.severity }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
          <p
            v-if="resultData.summary"
            class="result-summary"
            style="margin-top: 12px;">
            {{ resultData.summary }}
          </p>

          <div
            v-if="resultData.impactedNodes?.length"
            style="margin-top: 12px;">
            <h4>影响节点</h4>
            <el-tag
              v-for="n in resultData.impactedNodes"
              :key="n"
              size="small"
              style="margin: 2px;">
              {{ n }}
            </el-tag>
          </div>
          <div
            v-if="resultData.regressionScope?.length"
            style="margin-top: 12px;">
            <h4>回归范围</h4>
            <el-tag
              v-for="n in resultData.regressionScope"
              :key="n"
              size="small"
              type="warning"
              style="margin: 2px;">
              {{ n }}
            </el-tag>
          </div>
        </template>

        <!-- 迁移转换结果 -->
        <template v-if="resultType === 'migration' && resultData">
          <p
            v-if="resultData.summary"
            class="result-summary">
            {{ resultData.summary }}
          </p>

          <h4 v-if="resultData.changes?.length">转换清单</h4>
          <el-table
            v-if="resultData.changes?.length"
            :data="resultData.changes"
            size="small"
            stripe>
            <el-table-column
              prop="ruleType"
              label="规则"
              width="120" />
            <el-table-column
              prop="before"
              label="转换前" />
            <el-table-column
              prop="after"
              label="转换后" />
          </el-table>

          <div
            v-if="resultData.migratedCode"
            style="margin-top: 12px;">
            <h4>转换后代码</h4>
            <pre class="code-block"><code>{{ resultData.migratedCode }}</code></pre>
          </div>
        </template>

        <!-- PR 描述结果 -->
        <template v-if="resultType === 'pr' && resultData">
          <h4>提交信息</h4>
          <pre class="code-block"><code>{{ resultData.commitMessage }}</code></pre>

          <h4 style="margin-top: 12px;">PR 标题</h4>
          <el-tag
            size="large"
            type="primary">
            {{ resultData.prTitle }}
          </el-tag>

          <h4 style="margin-top: 12px;">PR 描述</h4>
          <div
            class="pr-body"
            v-html="renderMarkdown(resultData.prBody)" />
        </template>

        <!-- 通用 Agent 结果（fallback 为结构化 JSON） -->
        <template v-if="resultType === 'general' && resultData">
          <pre class="code-block"><code>{{ JSON.stringify(resultData, null, 2) }}</code></pre>
        </template>

        <!-- Fallback -->
        <template v-if="!resultType || !resultData">
          <pre class="code-block"><code>{{ resultText }}</code></pre>
        </template>
      </div>
      <template #footer>
        <el-button @click="resultVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Cpu, Promotion, QuestionFilled, Aim, EditPen
} from '@element-plus/icons-vue'
import { agentApi } from '@/api'
import {
  AGENTS, AGENT_COUNT, QUICK_REF,
  GENERAL_AGENT_TYPES, REFACTOR_SMELL_OPTIONS, MIGRATION_DIRECTION_OPTIONS,
  type AgentDef,
} from '@/constants/agents'

const route = useRoute()
const projectId = route.params.projectId as string

const helpOpen = ref<string[]>([])

const dialogVisible = ref(false)
const resultVisible = ref(false)
const executing = ref(false)
const resultText = ref('')
const resultType = ref('')
const resultData = ref<any>(null)
const activeAgent = ref<AgentDef | null>(null)

// 各 Agent 表单
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

// 通用 Agent 占位符
const generalPlaceholder = computed(() => {
  switch (generalForm.agentType) {
    case 'codefact': return '{\n  "codeContent": "public class UserService { ... }",\n  "sourcePath": "src/main/java/com/example/UserService.java"\n}'
    case 'docunderstanding': return '{\n  "docContent": "用户登录需求：用户输入账号密码...",\n  "sourcePath": "doc/需求文档.md"\n}'
    case 'featuremapping': return '{\n  "vueCode": "<template>...",\n  "apiDefinitions": "POST /api/login",\n  "controllerCode": "@PostMapping(\\"/login\\")"\n}'
    case 'testcasegeneration': return '{\n  "featureKey": "user-login",\n  "featureName": "用户登录",\n  "apiEndpoint": "POST /api/login"\n}'
    default: return '{}'
  }
})

// ===== 填入示例 =====
const fillGeneralExample = () => {
  switch (generalForm.agentType) {
    case 'codefact':
      generalForm.variables = JSON.stringify({ codeContent: 'public class UserService {\n    @Autowired\n    private UserMapper userMapper;\n\n    public User getUserById(Long id) {\n        return userMapper.selectById(id);\n    }\n}', sourcePath: 'src/main/java/com/example/UserService.java' }, null, 2)
      break
    case 'docunderstanding':
      generalForm.variables = JSON.stringify({ docContent: '## 用户登录功能\n用户通过输入用户名和密码进行登录，系统验证后返回JWT Token。\n密码连续错误3次锁定30分钟。', sourcePath: 'doc/需求文档/用户模块.md' }, null, 2)
      break
    default:
      generalForm.variables = JSON.stringify({ codeContent: '...', sourcePath: '...' }, null, 2)
  }
}
const fillSqlExample = () => {
  sqlForm.sqlKey = 'order_query'
  sqlForm.sql = 'SELECT o.*, u.name, u.email FROM orders o LEFT JOIN users u ON o.user_id = u.id WHERE o.status = \'pending\' AND o.created_at > \'2024-01-01\' ORDER BY o.created_at DESC'
  sqlForm.schemaInfo = 'CREATE TABLE orders (id BIGINT PRIMARY KEY, user_id BIGINT, status VARCHAR(20), amount DECIMAL(10,2), created_at TIMESTAMP);\nCREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(100), email VARCHAR(200));'
}
const fillTestExample = () => {
  testForm.featureKey = 'user-login'
  testForm.featureName = '用户登录'
  testForm.apiEndpoint = 'POST /api/auth/login'
  testForm.relatedTables = 'user, login_log'
  testForm.businessRules = '1. 密码错误3次锁定账户30分钟\n2. 登录成功后记录登录日志\n3. JWT Token 有效期2小时'
}
const fillReviewExample = () => {
  reviewForm.targetId = 'claim-20240701-001'
  reviewForm.targetType = 'KnowledgeClaim'
  reviewForm.content = '主张：UserService.createUser 方法负责用户注册\n证据：源码第45行 @PostMapping("/register")\n置信度：0.85'
}
const fillFailureExample = () => {
  failureForm.testCaseName = 'UserLoginTest.testLoginWithCorrectCredentials'
  failureForm.errorMessage = 'java.lang.AssertionError: Expected status 200 but got 401\n    at UserLoginTest.java:45'
  failureForm.environment = 'CI (GitHub Actions, JDK 17)'
  failureForm.recentChanges = '昨天修改了 JwtUtil 的密钥配置'
}
const fillReportExample = () => {
  reportForm.metrics = JSON.stringify({ totalNodes: 1520, apiCoverage: 0.65, testCoverage: 0.32, pendingReviews: 45, lowConfidenceNodes: 128 }, null, 2)
  reportForm.gaps = '订单模块缺少接口文档、支付模块无测试覆盖、用户模块有45条待审核主张'
}
const fillRefactorExample = () => {
  refactorForm.target = 'src/main/java/com/example/service/UserService.java'
  refactorForm.smellType = 'god_class'
}
const fillChangeExample = () => {
  changeForm.changeTarget = 'UserService.createUser'
  changeForm.changeDescription = '给 createUser 方法增加手机号校验逻辑，新增 phone 字段，校验手机号格式'
  changeForm.dependencies = 'UserController.createUser\nUserMapper.insert\nUserValidator.validatePhone'
}
const fillMigrationExample = () => {
  migrationForm.migrationDirection = 'xml2annotation'
  migrationForm.sourcePath = 'src/main/resources/mapper/UserMapper.xml'
  migrationForm.code = '<select id="findByStatus" resultType="User">\n  SELECT * FROM user WHERE status = #{status}\n</select>'
}
const fillPrExample = () => {
  prForm.branch = 'feature/user-login'
  prForm.issue = '#42 实现用户登录功能'
  prForm.diff = '+ 新增 UserController.java - POST /api/auth/login\n+ 新增 UserService.java - login 方法\n+ 新增 JwtUtil.java - Token 生成与验证\n~ 修改 application.yml - 增加 jwt.secret 配置'
}

const resultTitle = computed(() => {
  if (!activeAgent.value) return '执行结果'
  return `${activeAgent.value.title} · 分析结果`
})

const openAgent = (agent: AgentDef) => {
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
        try { vars = JSON.parse(generalForm.variables || '{}') } catch { /* empty */ }
        res = await agentApi.run({ agentType: generalForm.agentType, projectId, params: vars })
        break
      }
      case 'sql':
        res = await agentApi.analyzeSql({ projectId, sqlKey: sqlForm.sqlKey, sql: sqlForm.sql, schemaInfo: sqlForm.schemaInfo })
        break
      case 'test':
        res = await agentApi.generateTests({ projectId, featureKey: testForm.featureKey, featureName: testForm.featureName, apiEndpoint: testForm.apiEndpoint, relatedTables: testForm.relatedTables, businessRules: testForm.businessRules, httpMethod: 'GET' })
        break
      case 'review':
        res = await agentApi.reviewSuggest({ projectId, targetId: reviewForm.targetId, targetType: reviewForm.targetType, content: reviewForm.content })
        break
      case 'failure':
        res = await agentApi.analyzeTestFailure({ projectId, testCaseName: failureForm.testCaseName, errorMessage: failureForm.errorMessage, environment: failureForm.environment, recentChanges: failureForm.recentChanges })
        break
      case 'report':
        res = await agentApi.reportInsights({ projectId, metrics: reportForm.metrics, gaps: reportForm.gaps })
        break
      case 'refactor':
        res = await agentApi.refactorSuggest({ projectId, target: refactorForm.target, smellType: refactorForm.smellType, code: refactorForm.code })
        break
      case 'change':
        res = await agentApi.changeImpact({ projectId, changeTarget: changeForm.changeTarget, changeDescription: changeForm.changeDescription, dependencies: changeForm.dependencies })
        break
      case 'migration':
        res = await agentApi.migrationConvert({ projectId, migrationDirection: migrationForm.migrationDirection, sourcePath: migrationForm.sourcePath, code: migrationForm.code })
        break
      case 'pr':
        res = await agentApi.prDescribe({ projectId, branch: prForm.branch, issue: prForm.issue, diff: prForm.diff })
        break
    }

    const data = res?.data || res
    resultType.value = type as string
    resultData.value = data
    resultText.value = JSON.stringify(data, null, 2)
    dialogVisible.value = false
    resultVisible.value = true
    ElMessage.success('分析完成')
  } catch {
    ElMessage.error('执行失败，请检查输入参数')
  } finally {
    executing.value = false
  }
}

// ===== 辅助函数 =====
const riskType = (risk?: string) => {
  if (!risk) return 'info'
  if (risk.toLowerCase().includes('high') || risk.includes('高')) return 'danger'
  if (risk.toLowerCase().includes('medium') || risk.includes('中')) return 'warning'
  return 'success'
}
const severityType = (s?: string) => {
  if (!s) return 'info'
  if (s.toLowerCase().includes('high') || s.includes('严重')) return 'danger'
  if (s.toLowerCase().includes('medium') || s.includes('中等')) return 'warning'
  return 'success'
}
const priorityType = (p?: string) => {
  if (!p) return 'info'
  if (p.includes('P0') || p.includes('critical') || p.includes('紧急')) return 'danger'
  if (p.includes('P1') || p.includes('high') || p.includes('高')) return 'warning'
  if (p.includes('P2') || p.includes('medium') || p.includes('中')) return 'primary'
  return 'info'
}
const likelihoodType = (l?: string) => {
  if (!l) return 'info'
  if (l.toLowerCase().includes('high') || l.includes('高')) return 'danger'
  if (l.toLowerCase().includes('medium') || l.includes('中')) return 'warning'
  return 'success'
}
const renderMarkdown = (text?: string) => {
  if (!text) return ''
  return text
    .replace(/### (.+)/g, '<h4>$1</h4>')
    .replace(/## (.+)/g, '<h3>$1</h3>')
    .replace(/# (.+)/g, '<h2>$1</h2>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\n/g, '<br>')
}
</script>

<style scoped>
.agent-page { padding: 0; }
.help-collapse { background: #f0f9ff; border: 1px solid #b3d8ff; border-radius: 8px; }
.help-content { padding: 0 16px 16px; font-size: 14px; line-height: 1.8; color: #303133; }
.help-content ol { padding-left: 20px; }
.card-header { display: flex; align-items: center; }

.agent-card { cursor: pointer; transition: all 0.2s; height: 100%; display: flex; flex-direction: column; }
.agent-card:hover { transform: translateY(-2px); box-shadow: 0 4px 20px rgba(64, 158, 255, 0.15); }
.agent-card-top { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 8px; }
.agent-icon { color: #409eff; flex-shrink: 0; margin-top: 2px; }
.agent-title-row { flex: 1; min-width: 0; }
.agent-title-row h4 { margin: 0 0 4px 0; font-size: 15px; color: #303133; }
.agent-desc { font-size: 13px; color: #606266; margin: 0 0 10px; line-height: 1.5; min-height: 36px; }
.agent-extra { border-top: 1px solid #ebeef5; padding-top: 10px; margin-top: auto; }
.agent-scenario, .agent-input-hint { display: flex; align-items: flex-start; gap: 4px; font-size: 12px; color: #909399; line-height: 1.5; margin-bottom: 4px; }
.agent-scenario .el-icon, .agent-input-hint .el-icon { color: #c0c4cc; flex-shrink: 0; margin-top: 1px; }

.result-content { max-height: 65vh; overflow: auto; }
.result-summary { font-size: 14px; line-height: 1.7; color: #303133; background: #f5f7fa; padding: 12px 16px; border-radius: 6px; border-left: 3px solid #409eff; }
.result-issue { background: #fafafa; padding: 10px 14px; border-radius: 6px; margin-bottom: 8px; }
.result-issue p { margin: 6px 0 0; font-size: 13px; line-height: 1.6; }
.issue-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.code-block { background: #1e1e1e; color: #d4d4d4; padding: 14px 16px; border-radius: 6px; font-size: 13px; line-height: 1.6; overflow-x: auto; white-space: pre-wrap; word-break: break-word; }
.pr-body { background: #fafafa; padding: 12px 16px; border-radius: 6px; font-size: 14px; line-height: 1.8; }
.pr-body :deep(code) { background: #e8e8e8; padding: 2px 6px; border-radius: 3px; font-size: 13px; }
</style>
