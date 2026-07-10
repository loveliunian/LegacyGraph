-- ============================================
-- V54: 测试生成（TEST_GENERATION）提示词模板种子数据
-- --------------------------------------------
-- 背景：QueryIntent 新增 TEST_GENERATION 意图，用于识别"帮 XX 方法生成单元测试/
--   补齐测试"类测试生成查询。
--   配套该意图，新增"测试生成"提示词模板，输出结构含：
--     1. 测试目标（方法签名 + 依赖列表）
--     2. 测试场景（正常路径/参数校验失败/依赖异常/边界条件）
--     3. 测试代码（JUnit 5 + Mockito @ExtendWith/@Mock/@InjectMocks）
--     4. 覆盖建议
--   同步落地于 classpath:/prompts/test-generation.txt（DB 缺失时回退路径）。
--   LlmGateway 调用时 responseType=String.class → 直接返回测试代码文本。
-- 字段沿用 V12__seed_prompt_templates.sql / V49 / V50 / V51 / V52 / V53 的格式。
-- ============================================

INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'test-generation',
    '1.0',
    'code',
    '你是一个测试代码生成专家。请基于图谱上下文（目标方法签名、依赖列表、契约信息、现有测试），生成完整的 JUnit 5 单元测试代码。使用 @ExtendWith(MockitoExtension.class) + @Mock + @InjectMocks 模式，覆盖正常路径、参数校验失败、依赖异常和边界条件。',
    NULL,
    '## 目标方法
- 类名: {className}
- 方法名: {methodName}
- 方法签名: {methodSignature}

## 依赖列表（需 mock 的对象）
{dependencies}

## 契约信息（如适用）
{contractInfo}

## 现有测试（避免重复）
{existingTests}

## 输出要求

### 测试目标
- 方法签名
- 依赖列表

### 测试场景
1. 正常路径
2. 参数校验失败
3. 依赖异常
4. 边界条件

### 测试代码
```java
@ExtendWith(MockitoExtension.class)
class XxxTest {
    @Mock DependencyClass dependency;
    @InjectMocks TargetClass target;

    @Test
    void methodName_validInput_returnsExpected() { ... }

    @Test
    void methodName_invalidInput_throwsException() { ... }
}
```

### 覆盖建议
- 建议补充的场景

## 重要约束
- 只依据上下文中的方法签名和依赖列表生成，不得编造不存在的依赖。
- 测试类名格式：{TargetClassSimpleName}Test
- 每个测试方法名须语义化：methodName_场景描述_预期结果
- Mock 依赖的行为须与图谱中的 CALLS 关系一致。',
    '{
  "testTarget": {
    "className": "com.example.OrderService",
    "methodName": "createOrder",
    "methodSignature": "createOrder(OrderDTO)"
  },
  "dependencies": ["OrderMapper (insert)", "OrderValidator (validate)"],
  "testScenarios": [
    "正常路径：有效订单 → 返回订单ID",
    "参数校验失败：空订单号 → 抛出 IllegalArgumentException",
    "依赖异常：Mapper.insert 失败 → 抛出 RuntimeException",
    "边界条件：null 参数 → 抛出 NullPointerException"
  ],
  "testCode": "@ExtendWith(MockitoExtension.class)\\nclass OrderServiceTest { ... }",
  "coverageSuggestions": ["建议补充并发场景测试", "建议补充大订单量性能测试"]
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;
