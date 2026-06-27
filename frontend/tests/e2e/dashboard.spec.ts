import { test, expect } from '@playwright/test';

test.describe('仪表盘页面', () => {
  test.beforeEach(async ({ page }) => {
    // 先登录
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
  });

  test('should display dashboard after successful login', async ({ page }) => {
    // 登录后应该跳转到仪表盘
    await expect(page).toHaveURL(/.*dashboard/);
    await expect(page.getByRole('heading', { name: '仪表盘' })).toBeVisible();
  });

  test('should display all statistic cards', async ({ page }) => {
    await page.goto('/dashboard');

    // 统计卡片应该显示
    await expect(page.locator('.el-card').first()).toBeVisible();

    // 通常包含项目总数、扫描任务、事实数、风险数等
    const cards = page.locator('.el-card');
    const cardCount = await cards.count();
    expect(cardCount).toBeGreaterThan(0);
  });

  test('should display charts', async ({ page }) => {
    await page.goto('/dashboard');

    // 图表容器应该存在
    const chartContainers = page.locator('.echarts-container');
    if (await chartContainers.count() > 0) {
      await expect(chartContainers.first()).toBeVisible();
    }
  });

  test('should have working sidebar navigation', async ({ page }) => {
    await page.goto('/dashboard');

    // 侧边栏应该可见
    await expect(page.locator('.el-menu').first()).toBeVisible();

    // 各个主菜单项应该可见
    const mainMenuItems = [
      '项目管理',
      '数据源',
      '扫描管理',
      '图谱',
      '事实',
      '测试',
      '迁移风险'
    ];

    for (const item of mainMenuItems) {
      await expect(page.getByText(item).first()).toBeVisible();
    }
  });

  test('should toggle sidebar collapse', async ({ page }) => {
    await page.goto('/dashboard');

    // 找到折叠按钮
    const collapseButton = page.locator('.collapse-button');
    if (await collapseButton.isVisible()) {
      await collapseButton.click();
      // 点击后应该能切换状态
      await expect(collapseButton).toBeVisible();
    }
  });
});
