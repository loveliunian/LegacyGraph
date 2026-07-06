-- V37: 修复 qa-answer-enhanced 流式输出显示原始 JSON 的问题
-- output_schema 列有 NOT NULL 约束，设为空 JSON 字符串（\"\"）
-- PromptTemplateLoader.renderFromDb() 检查 output_schema != null && !isBlank()
-- 空字符串 isBlank()=true，不会追加 JSON 格式指令，LLM 直接输出纯文本
UPDATE lg_prompt_template
SET output_schema = '\"\"'::jsonb
WHERE template_code = 'qa-answer-enhanced'
  AND output_schema IS NOT NULL
  AND output_schema::text NOT IN ('""', '""');
