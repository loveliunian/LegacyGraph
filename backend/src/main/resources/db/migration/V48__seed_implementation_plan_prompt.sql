-- ============================================
-- V48: P3-8 新增 implementation-plan 提示词模板种子数据
-- --------------------------------------------
-- 背景：P3-7 新增 IMPLEMENTATION_PLAN 意图（QueryIntent 枚举），
--   用于识别"做一个/实现/开发/新增 XX 功能/需求/模块/接口"类需求。
--   P3-8 配套该意图，新增"实施方案生成"提示词模板，输出结构含：
--     1. 需求分解
--     2. 实现方案（按分层：数据库/实体/Mapper/Service/Controller/前端）
--     3. 可复用组件
--     4. 风险与注意事项
--     5. 实施步骤（DDL→实体→Mapper→Service→Controller→前端→测试）
--   占位符：
--     {requirement} 接收用户需求
--     {context}    接收检索上下文（图谱节点 / 文档片段 / 已有实现）
--   同步落地于 classpath:/prompts/implementation-plan.txt（DB 缺失时回退路径，
--   见 PromptTemplateLoader）。
-- 字段沿用 V12__seed_prompt_templates.sql 的格式。
-- ============================================

INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'implementation-plan',
    '1.0',
    'code',
    '你是一位资深全栈架构师。请基于用户需求与检索到的代码图谱/文档上下文，给出一份可直接落地的分层实施方案。',
    NULL,
    '## 用户需求
{requirement}

## 检索到的上下文（图谱节点 / 文档片段 / 已有实现）
{context}

## 输出要求
方案须包含以下章节，按顺序输出，缺失项填"（无）"并说明原因：

### 1. 需求分解
- 把用户需求拆成若干可独立验收的子需求（subRequirements）。
- 每个子需求标注所属层（数据库/实体/Mapper/Service/Controller/前端/测试）与验收标准。

### 2. 实现方案（按分层）
按以下分层逐一给出实现要点；每层若无需改动填"（无）"：
- 数据库层：表/字段/索引/DDL
- 实体层：Entity / DTO / VO
- Mapper 层：Mapper 接口 / SQL（含分页、批量）
- Service 层：业务逻辑、事务边界、对外暴露方法
- Controller 层：接口路径、入参出参、鉴权
- 前端层：页面/组件、接口对接、状态管理
每层给出"新建/修改"标注与文件路径建议（路径基于上下文中真实存在的目录，勿编造）。

### 3. 可复用组件
- 列出上下文中已存在、可直接复用的 Controller/Service/工具类/前端组件。
- 每项标注复用方式（直接调用/继承/组合）与所在文件。

### 4. 风险与注意事项
- 性能（N+1、大结果集）、事务一致性、并发、安全（越权/SQL 注入）、向后兼容。
- 每项给出 severity（HIGH/MEDIUM/LOW）与规避建议。

### 5. 实施步骤
按顺序给出可执行步骤，每步列出具体文件路径与方法名：
DDL → 实体 → Mapper → Service → Controller → 前端 → 测试

## 重要约束
- 只依据上下文推断已有目录结构与可复用实现，不得编造上下文中不存在的表名、接口、模块。
- 上下文不足以确定的部分，明确标注"（推断）"或"待确认"。
- 不输出 diff，只输出方案。',
    '{
  "summary": "方案一句话总览",
  "subRequirements": [
    { "name": "子需求名", "layer": "数据库", "acceptance": "验收标准" }
  ],
  "layers": {
    "database":   { "changes": [ { "type": "NEW|MODIFY", "path": "db/migration/Vxx__xxx.sql", "detail": "建表/加字段/索引" } ], "note": "（无）" },
    "entity":     { "changes": [ { "type": "NEW", "path": ".../OrderExport.java", "detail": "导出 DTO" } ], "note": "" },
    "mapper":     { "changes": [ { "type": "NEW", "path": ".../OrderMapper.java", "detail": "导出查询 SQL" } ], "note": "" },
    "service":    { "changes": [ { "type": "NEW", "path": ".../OrderExportService.java", "detail": "导出业务编排" } ], "note": "" },
    "controller": { "changes": [ { "type": "NEW", "path": ".../OrderExportController.java", "detail": "导出接口" } ], "note": "" },
    "frontend":   { "changes": [ { "type": "NEW", "path": "src/views/order/Export.vue", "detail": "导出按钮与下载" } ], "note": "" }
  },
  "reusableComponents": [
    { "name": "FileDownloader", "path": ".../util/FileDownloader.java", "reuseWay": "直接调用", "reason": "已支持流式下载" }
  ],
  "risks": [
    { "category": "性能", "severity": "MEDIUM", "description": "全量导出可能 OOM", "mitigation": "分页/流式写入" }
  ],
  "steps": [
    { "order": 1, "layer": "DDL", "target": "新建 order_export_task 表", "files": ["db/migration/Vxx__order_export_task.sql"] },
    { "order": 2, "layer": "实体", "target": "新建 OrderExportTask 实体", "files": [".../entity/OrderExportTask.java"] },
    { "order": 3, "layer": "Mapper", "target": "新增导出查询", "files": [".../mapper/OrderMapper.java"] },
    { "order": 4, "layer": "Service", "target": "导出业务编排", "files": [".../service/OrderExportService.java"] },
    { "order": 5, "layer": "Controller", "target": "导出接口", "files": [".../controller/OrderExportController.java"] },
    { "order": 6, "layer": "前端", "target": "导出按钮与下载", "files": ["src/views/order/Export.vue"] },
    { "order": 7, "layer": "测试", "target": "单元与集成测试", "files": [".../OrderExportServiceTest.java"] }
  ]
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;
