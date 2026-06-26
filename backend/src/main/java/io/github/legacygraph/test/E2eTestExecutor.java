package io.github.legacygraph.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.TestResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * E2E测试执行器
 * 使用Playwright执行前端页面自动化测试
 *
 * 注：实际执行需要Node.js环境 + Playwright，这里Java层只做任务调度和结果收集
 */
@Slf4j
@Component
public class E2eTestExecutor {

    private final TestResultRepository testResultRepository;
    private final ObjectMapper objectMapper;

    public E2eTestExecutor(TestResultRepository testResultRepository,
                          ObjectMapper objectMapper) {
        this.testResultRepository = testResultRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成Playwright测试代码
     * 根据测试用例生成TypeScript测试代码
     */
    public String generatePlaywrightTestCode(TestCase testCase) {
        List<Map<String, Object>> steps = parseJson(testCase.getSteps(), List.class);
        StringBuilder code = new StringBuilder();

        code.append("import { test, expect } from '@playwright/test';\n\n");
        code.append("test('").append(testCase.getCaseName()).append("', async ({ page }) => {\n");

        for (var step : steps) {
            String action = (String) step.get("action");
            if ("NAVIGATE".equals(action)) {
                String url = (String) step.get("url");
                code.append("  await page.goto('").append(url).append("');\n");
            } else if ("FILL".equals(action)) {
                String selector = (String) step.get("selector");
                String value = (String) step.get("value");
                code.append("  await page.fill('").append(selector).append("', '").append(value).append("');\n");
            } else if ("CLICK".equals(action)) {
                String selector = (String) step.get("selector");
                code.append("  await page.click('").append(selector).append("');\n");
            } else if ("ASSERT_VISIBLE".equals(action)) {
                String selector = (String) step.get("selector");
                code.append("  await expect(page.locator('").append(selector).append("')).toBeVisible();\n");
            } else if ("ASSERT_CONTAINS".equals(action)) {
                String selector = (String) step.get("selector");
                String text = (String) step.get("text");
                code.append("  await expect(page.locator('").append(selector).append("')).toContainText('").append(text).append("');\n");
            }
        }

        code.append("});\n");
        return code.toString();
    }

    /**
     * 执行E2E测试
     * 实际执行会调用playwright CLI，这里保存结果
     */
    @Transactional
    public TestResult execute(TestCase testCase, String executionId, String baseUrl) {
        log.info("Preparing E2E test: {}", testCase.getCaseCode());

        TestResult result = new TestResult();
        result.setId(UUID.randomUUID().toString());
        result.setProjectId(testCase.getProjectId());
        result.setVersionId(testCase.getVersionId());
        result.setTestCaseId(testCase.getId());
        result.setExecutionId(executionId);
        result.setExecutedAt(LocalDateTime.now());

        // TODO: 实际调用playwright-test 执行生成的测试代码
        // 这里只创建记录，实际执行由外部调度

        result.setResultStatus("SCHEDULED");
        testResultRepository.save(result);

        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> T parseJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }
}
