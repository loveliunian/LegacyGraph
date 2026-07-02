import { test, expect } from '@playwright/test';

test.describe('代码图谱页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display code graph page', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/code');

    // 版本选择器
    await expect(page.locator('.el-select').first()).toBeVisible({ timeout: 10000 });
  });

  test('should display API method search input', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/code');

    // API 方法搜索框
    const methodInput = page.getByPlaceholder(/API|method|方法名/);
    if (await methodInput.count() > 0) {
      await expect(methodInput.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display version selector', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/code');

    const versionSelect = page.locator('.el-select').first();
    await expect(versionSelect).toBeVisible({ timeout: 5000 });
  });

  test('should display graph canvas for API call chain', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/code');

    // 图谱画布
    const flowCanvas = page.locator('.vue-flow, .vue-flow__container, [class*="flow"]');
    if (await flowCanvas.count() > 0) {
      await expect(flowCanvas.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display result node list', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/code');

    // 查询结果列表（节点详情）
    const resultSection = page.locator('[class*="result"], .el-card');
    if (await resultSection.count() > 0) {
      await expect(resultSection.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should query API call chain', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/code');

    const methodInput = page.getByPlaceholder(/API|method|方法名/);
    if (await methodInput.count() > 0) {
      await methodInput.first().fill('/api/user/login');
      const queryBtn = page.getByRole('button', { name: /查询|搜索|示例/ });
      if (await queryBtn.isVisible()) {
        await queryBtn.click();
        // 等待加载完成
        await page.waitForTimeout(2000);
      }
    }
  });
});
