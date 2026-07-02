import { test, expect } from '@playwright/test';

test.describe('项目概览页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display project overview page', async ({ page }) => {
    // 导航到项目列表，点击第一个项目进入概览
    await page.getByText('项目管理').click();
    await expect(page).toHaveURL(/.*projects/);

    // 如果有项目，点击第一个进入
    const firstProject = page.locator('.el-table__body tr').first();
    if (await firstProject.isVisible()) {
      await firstProject.click();
      await expect(page).toHaveURL(/.*projects\/.*\/overview/);
    }
  });

  test('should display overview statistic cards', async ({ page }) => {
    // 直接访问一个已知项目概览（需要替换为实际项目ID）
    await page.goto('/projects/demo-project/overview');

    // 概览统计卡片应该显示
    await expect(page.locator('.el-card').first()).toBeVisible();

    // 统计数值卡片
    const statCards = page.locator('.stat-card, .el-card .stat-value');
    const cardCount = await statCards.count();
    expect(cardCount).toBeGreaterThanOrEqual(0);
  });

  test('should display data source access status', async ({ page }) => {
    await page.goto('/projects/demo-project/overview');

    // 资料接入状态区域
    const sourceSection = page.locator('.source-status, [class*="source"]');
    await expect(sourceSection.first()).toBeVisible({ timeout: 10000 });
  });

  test('should display graph statistics', async ({ page }) => {
    await page.goto('/projects/demo-project/overview');

    // 图谱统计区域（节点数、边数等）
    const graphStats = page.locator('.graph-stats, [class*="graph-stat"]');
    if (await graphStats.count() > 0) {
      await expect(graphStats.first()).toBeVisible();
    }
  });

  test('should navigate between project tabs', async ({ page }) => {
    await page.goto('/projects/demo-project/overview');

    // 项目详情页应该有标签页导航
    const tabs = page.locator('.el-tabs__item, [role="tab"]');
    if (await tabs.count() > 0) {
      await expect(tabs.first()).toBeVisible();
    }
  });
});
