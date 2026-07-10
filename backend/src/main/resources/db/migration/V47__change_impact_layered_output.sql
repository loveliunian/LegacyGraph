-- ============================================
-- V47: P2-6 change-impact 模板追加"输出结构"分层要求
-- --------------------------------------------
-- 背景：EnhancedQaAgent.appendChangeImpactContext 已在 QA 上下文侧要求
--   LLM 产出"受影响清单 + 执行步骤 + 建议"，但 change-impact 模板侧
--   未对齐，导致 LLM 输出的 impactedNodes / summary 字段层次不稳定。
-- 本次更新：
--   1. 分析要求新增第 6 条：impactedNodes 须覆盖
--      表→SQL→Mapper→Service→Controller→前端 各层节点。
--   2. 新增"## 输出结构"小节，要求 summary 字段按分层结构组织：
--      ① 受影响清单（按 表→SQL→Mapper→Service→Controller→前端 分层）
--      ② 执行步骤（DDL→实体→Mapper→Service→Controller→前端→测试，
--         每步列出具体文件路径和方法名）
--      ③ 风险等级与回归范围
--   保持 JSON 输出格式不变，不破坏 ChangeImpactAnalysis DTO 反序列化。
-- 同步更新 classpath:/prompts/change-impact.txt（DB 缺失时的回退路径）。
-- ============================================

UPDATE lg_prompt_template
SET task_prompt = '## 变更信息
- 变更目标: {changeTarget}
- 变更描述/diff: {changeDescription}
- 图谱依赖节点: {dependencies}

## 分析要求
1. 判断修改类型 changeType：BUGFIX、FEATURE、BREAKING_CHANGE、REFACTOR。
2. 评估业务影响严重程度 severity：HIGH、MEDIUM、LOW。
3. 预测需要重跑的测试范围 affectedTests。
4. 建议重点回归范围 regressionScope。
5. 列出受影响的关键节点 impactedNodes（来自依赖节点，勿编造）。
6. impactedNodes 须覆盖 表→SQL→Mapper→Service→Controller→前端 各层节点；某层无受影响节点时填"（无）"。

## 输出结构
为保证下游 QA Agent 能稳定产出"受影响清单 + 执行步骤"，summary 字段须按以下分层组织（多行字符串，换行分隔）：
1. 受影响清单（按 表→SQL→Mapper→Service→Controller→前端 分层，列出每层受影响节点）
2. 执行步骤（DDL→实体→Mapper→Service→Controller→前端→测试，每步列出具体文件路径和方法名；无证据的部分标注"推断"）
3. 风险等级与回归范围（与 severity、regressionScope 对齐，并简述判定理由）'
WHERE template_code = 'change-impact'
  AND is_active = TRUE;
