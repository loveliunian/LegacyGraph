package io.github.legacygraph.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.TestResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import io.github.legacygraph.util.IdUtil;

/**
 * E2E测试执行器
 * 使用Playwright执行前端页面自动化测试
 *
 * 工作流程：
 * 1. 根据测试用例生成 TypeScript 测试代码
 * 2. 写入临时测试文件
 * 3. 调用 npx playwright test 执行
 * 4. 解析 JSON 测试结果
 * 5. 保存测试结果到数据库
 */
@Slf4j
@Component
public class E2eTestExecutor {

    private final TestResultRepository testResultRepository;
    private final ObjectMapper objectMapper;

    @Value("${legacygraph.e2e.temp-dir:/tmp/legacygraph-e2e}")
    private String tempDir;

    @Value("${legacygraph.e2e.timeout-seconds:300}")
    private int timeoutSeconds;

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
        code.append("test('").append(escapeJs(testCase.getCaseName())).append("', async ({ page }) => {\n");

        if (steps != null) {
            for (var step : steps) {
                String action = (String) step.get("action");
                if ("NAVIGATE".equals(action)) {
                    String url = (String) step.get("url");
                    code.append("  await page.goto('").append(escapeJs(url)).append("');\n");
                } else if ("FILL".equals(action)) {
                    String selector = (String) step.get("selector");
                    Object value = step.get("value");
                    code.append("  await page.fill('").append(escapeJs(selector)).append("', '").append(escapeJs(String.valueOf(value))).append("');\n");
                } else if ("CLICK".equals(action)) {
                    String selector = (String) step.get("selector");
                    code.append("  await page.click('").append(escapeJs(selector)).append("');\n");
                } else if ("ASSERT_VISIBLE".equals(action)) {
                    String selector = (String) step.get("selector");
                    code.append("  await expect(page.locator('").append(escapeJs(selector)).append("')).toBeVisible();\n");
                } else if ("ASSERT_CONTAINS".equals(action)) {
                    String selector = (String) step.get("selector");
                    String text = (String) step.get("text");
                    code.append("  await expect(page.locator('").append(escapeJs(selector)).append("')).toContainText('").append(escapeJs(text)).append("');\n");
                } else if ("ASSERT_HIDDEN".equals(action)) {
                    String selector = (String) step.get("selector");
                    code.append("  await expect(page.locator('").append(escapeJs(selector)).append("')).toBeHidden();\n");
                } else if ("SCREENSHOT".equals(action)) {
                    code.append("  await page.screenshot({ path: 'screenshot.png' });\n");
                }
            }
        }

        code.append("});\n");
        return code.toString();
    }

    /**
     * 执行E2E测试
     * 实际调用playwright CLI执行
     */
    @Transactional
    public TestResult execute(TestCase testCase, String executionId, String baseUrl) {
        log.info("Executing E2E test: {} {}", testCase.getCaseCode(), testCase.getCaseName());

        long startTime = System.currentTimeMillis();

        TestResult result = new TestResult();
        result.setId(IdUtil.fastUUID());
        result.setProjectId(testCase.getProjectId());
        result.setVersionId(testCase.getVersionId());
        result.setTestCaseId(testCase.getId());
        result.setExecutionId(executionId);
        result.setExecutedAt(LocalDateTime.now());

        try {
            // 1. 确保临时目录存在
            Path tempPath = Path.of(tempDir);
            if (!Files.exists(tempPath)) {
                Files.createDirectories(tempPath);
            }

            // 2. 生成测试代码写入临时文件
            String testCode = generatePlaywrightTestCode(testCase);
            String testFileName = String.format("e2e-%s.test.ts", result.getId());
            Path testFile = tempPath.resolve(testFileName);
            Files.write(testFile, testCode.getBytes(StandardCharsets.UTF_8));

            // 3. 执行 playwright test
            ProcessBuilder pb = new ProcessBuilder(
                    "npx", "playwright", "test", testFile.toString(),
                    "--reporter", "json",
                    "--output", tempDir
            );
            pb.directory(new File(tempDir));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                // 超时
                process.destroy();
                result.setResultStatus("FAILED");
                result.setErrorMessage("Test execution timed out after " + timeoutSeconds + " seconds");
            } else {
                int exitCode = process.exitValue();
                // 读取输出
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                log.info("Playwright output for test {}: {}", result.getId(), output);

                if (exitCode == 0) {
                    result.setResultStatus("PASSED");
                } else {
                    result.setResultStatus("FAILED");
                    result.setErrorMessage("Exit code " + exitCode + "\n" + output);
                }
            }

            // 清理临时文件（可选保留用于调试）
            Files.deleteIfExists(testFile);

        } catch (Exception e) {
            log.error("E2E test execution failed: {}", testCase.getCaseCode(), e);
            result.setResultStatus("ERROR");
            result.setErrorMessage(e.getMessage());
        }

        result.setDurationMs(System.currentTimeMillis() - startTime);
        testResultRepository.insert(result);

        log.info("E2E test completed: {} status={}", result.getId(), result.getResultStatus());
        return result;
    }

    /**
     * 转义 JavaScript 字符串中的单引号
     */
    private String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'");
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
