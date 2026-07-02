-- ============================================
-- V18: 修复 prompt 模板 output_schema 与 Java DTO 字段不一致
-- ============================================
-- 问题：DB 中的 output_schema 字段名/结构未与 Java 响应类对齐，
-- 导致 LLM 按 schema 输出后 Jackson 反序列化失败（如 Unrecognized field）。
--
-- 修复范围：
--   1. doc-understanding: 旧 schema 只有 processes，改为 BusinessFactExtraction 的 8 个字段
--   2. test-case-generation: 字段直接放根级，改为 TestCaseGenerationResult 包裹 testCases 数组
--   3. graph-rag-planner: SubQuestion.dependsOn 类型从 array 改为 integer

-- ============================================
-- 1. doc-understanding → BusinessFactExtraction
-- ============================================
UPDATE lg_prompt_template
SET output_schema = '{
  "type": "object",
  "title": "BusinessFactExtraction",
  "properties": {
    "businessDomains": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["name"],
        "properties": {
          "name": {"type": "string"},
          "description": {"type": "string"},
          "confidence": {"type": "number", "minimum": 0, "maximum": 1},
          "evidenceText": {"type": "string"}
        }
      }
    },
    "businessProcesses": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["key", "name", "description"],
        "properties": {
          "key": {"type": "string"},
          "name": {"type": "string"},
          "description": {"type": "string"},
          "steps": {"type": "array", "items": {"type": "string"}},
          "roles": {"type": "array", "items": {"type": "string"}},
          "objects": {"type": "array", "items": {"type": "string"}},
          "rules": {"type": "array", "items": {"type": "string"}},
          "confidence": {"type": "number", "minimum": 0, "maximum": 1},
          "evidence": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "sourceType": {"type": "string"},
                "sourceUri": {"type": "string"},
                "lineStart": {"type": "integer"},
                "lineEnd": {"type": "integer"},
                "excerpt": {"type": "string"}
              }
            }
          }
        }
      }
    },
    "businessObjects": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["name"],
        "properties": {
          "name": {"type": "string"},
          "description": {"type": "string"},
          "attributes": {"type": "array", "items": {"type": "string"}},
          "confidence": {"type": "number", "minimum": 0, "maximum": 1},
          "evidence": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "sourceType": {"type": "string"},
                "sourceUri": {"type": "string"},
                "lineStart": {"type": "integer"},
                "lineEnd": {"type": "integer"},
                "excerpt": {"type": "string"}
              }
            }
          }
        }
      }
    },
    "businessRules": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["name", "expression"],
        "properties": {
          "name": {"type": "string"},
          "expression": {"type": "string"},
          "confidence": {"type": "number", "minimum": 0, "maximum": 1},
          "evidence": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "sourceType": {"type": "string"},
                "sourceUri": {"type": "string"},
                "lineStart": {"type": "integer"},
                "lineEnd": {"type": "integer"},
                "excerpt": {"type": "string"}
              }
            }
          }
        }
      }
    },
    "statusTransitions": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["businessObject", "fromStatus", "toStatus", "trigger"],
        "properties": {
          "businessObject": {"type": "string"},
          "fromStatus": {"type": "string"},
          "toStatus": {"type": "string"},
          "trigger": {"type": "string"},
          "confidence": {"type": "number", "minimum": 0, "maximum": 1},
          "evidence": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "sourceType": {"type": "string"},
                "sourceUri": {"type": "string"},
                "lineStart": {"type": "integer"},
                "lineEnd": {"type": "integer"},
                "excerpt": {"type": "string"}
              }
            }
          }
        }
      }
    },
    "roles": {
      "type": "array",
      "items": {"type": "string"}
    },
    "features": {
      "type": "array",
      "items": {"type": "string"}
    },
    "evidence": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["chunkTitle", "contentExcerpt", "chunkIndex"],
        "properties": {
          "chunkTitle": {"type": "string"},
          "contentExcerpt": {"type": "string"},
          "chunkIndex": {"type": "integer"}
        }
      }
    }
  }
}'::JSONB
WHERE template_code = 'doc-understanding' AND is_active = TRUE;

-- ============================================
-- 2. test-case-generation → TestCaseGenerationResult
-- ============================================
UPDATE lg_prompt_template
SET output_schema = '{
  "type": "object",
  "title": "TestCaseGenerationResult",
  "properties": {
    "testCases": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["caseName", "caseType"],
        "properties": {
          "featureKey": {"type": "string"},
          "caseName": {"type": "string"},
          "caseType": {"enum": ["API", "E2E", "DB", "HYBRID"], "type": "string"},
          "preconditions": {"type": "array", "items": {"type": "string"}},
          "steps": {"type": "array", "items": {"type": "string"}},
          "request": {"type": "object", "additionalProperties": true},
          "assertions": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["type", "expression"],
              "properties": {
                "type": {"enum": ["HTTP", "JSON_PATH", "SQL", "STATE", "UI"], "type": "string"},
                "expression": {"type": "string"}
              }
            }
          },
          "needHumanInput": {"type": "array", "items": {"type": "string"}}
        }
      }
    }
  }
}'::JSONB
WHERE template_code = 'test-case-generation' AND is_active = TRUE;

-- ============================================
-- 3. graph-rag-planner: dependsOn array → integer
-- ============================================
UPDATE lg_prompt_template
SET output_schema = jsonb_set(
  output_schema,
  '{properties,subQuestions,items,properties,dependsOn}',
  '{"type": "integer", "default": -1}'::JSONB
)
WHERE template_code = 'graph-rag-planner' AND is_active = TRUE;
