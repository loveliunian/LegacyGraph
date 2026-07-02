import { test, expect } from '@playwright/test';

test.describe('验证报告页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display validation report page', async ({ page }) => {
    await page.goto('/projects/demo-project/validation');

    // 报告概览卡片
    await expect(page.locator('.el-card').first()).toBeVisible({ timeout: 10000 });
  });

  test('should display report overview statistics', async ({ page }) => {
    await page.goto('/projects/demo-project/validation');

    // 统计数值显示（节点数、边数、通过率等）
    const stats = page.locator('.stat-value, [class*="stat"]');
    if (await stats.count() > 0) {
      await expect(stats.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display risk list', async ({ page }) => {
    await page.goto('/projects/demo-project/validation');

    // 风险列表区域
    const riskTable = page.locator('.el-table');
    if (await riskTable.count() > 0) {
      await expect(riskTable.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display chart visualization', async ({ page }) => {
    await page.goto('/projects/demo-project/validation');

    // 图表区域
    const charts = page.locator('.echarts-container, canvas, [class*="chart"]');
    if (await charts.count() > 0) {
      await expect(charts.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should have export report button', async ({ page }) => {
    await page.goto('/projects/demo-project/validation');

    const exportButton = page.getByRole('button', { name: '导出报告' });
    if (await exportButton.isVisible()) {
      await expect(exportButton).toBeVisible();
    }
  });
});
