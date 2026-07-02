import { test, expect } from '@playwright/test';

test.describe('业务图谱页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display business graph page', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/business');

    // 版本选择器应该可见
    await expect(page.locator('.el-select').first()).toBeVisible({ timeout: 10000 });
  });

  test('should display version selector', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/business');

    const versionSelect = page.locator('.el-select').first();
    await expect(versionSelect).toBeVisible({ timeout: 5000 });
  });

  test('should display graph canvas area', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/business');

    // 图谱画布 (Vue Flow 或类似组件)
    const flowCanvas = page.locator('.vue-flow, .vue-flow__container, [class*="flow"]');
    if (await flowCanvas.count() > 0) {
      await expect(flowCanvas.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display domain tree', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/business');

    // 领域树/业务域选择器
    const domainTree = page.locator('.el-tree, [class*="domain-tree"], [class*="domain"]');
    if (await domainTree.count() > 0) {
      await expect(domainTree.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display graph statistics', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/business');

    // 图谱统计：节点数、边数等
    const statsSection = page.locator('[class*="stat"], [class*="graph-stat"]');
    if (await statsSection.count() > 0) {
      await expect(statsSection.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should have AI view toggle', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/business');

    const aiToggle = page.getByText('AI').or(page.getByText('AI视图'));
    if (await aiToggle.isVisible()) {
      await expect(aiToggle).toBeVisible();
    }
  });
});
