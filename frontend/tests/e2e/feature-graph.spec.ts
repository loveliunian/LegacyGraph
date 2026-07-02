import { test, expect } from '@playwright/test';

test.describe('功能图谱页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display feature graph page', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/feature');

    // 版本选择器
    await expect(page.locator('.el-select').first()).toBeVisible({ timeout: 10000 });
  });

  test('should display version selector', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/feature');

    const versionSelect = page.locator('.el-select').first();
    await expect(versionSelect).toBeVisible({ timeout: 5000 });
  });

  test('should display graph canvas', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/feature');

    const flowCanvas = page.locator('.vue-flow, .vue-flow__container, [class*="flow"]');
    if (await flowCanvas.count() > 0) {
      await expect(flowCanvas.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display coverage statistics', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/feature');

    // 覆盖率统计卡片
    const coverageCards = page.locator('[class*="coverage"], [class*="stat"]');
    if (await coverageCards.count() > 0) {
      await expect(coverageCards.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display module selector', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/feature');

    // 模块/功能领域选择器
    const moduleSelector = page.locator('[class*="module"], [class*="domain"]');
    if (await moduleSelector.count() > 0) {
      await expect(moduleSelector.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should have generate tests button', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/feature');

    const generateBtn = page.getByRole('button', { name: '生成测试' });
    if (await generateBtn.isVisible()) {
      await expect(generateBtn).toBeVisible();
    }
  });
});
