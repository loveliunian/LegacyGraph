import { test, expect } from '@playwright/test';

test.describe('Smoke Tests - 应用启动检查', () => {
  test('should load application and login page', async ({ page }) => {
    await page.goto('/');

    // 如果未登录，应该重定向到登录页
    const currentUrl = page.url();
    if (currentUrl.includes('/login')) {
      // 检查登录页关键元素
      await expect(page.getByLabel('用户名')).toBeVisible();
      await expect(page.getByLabel('密码')).toBeVisible();
      await expect(page.getByRole('button', { name: '登录' })).toBeVisible();
      console.log('✓ 登录页加载成功');
    } else {
      // 已经登录，检查主布局
      await expect(page.locator('.app-container')).toBeVisible();
      await expect(page.locator('.el-menu')).toBeVisible();
      console.log('✓ 主应用加载成功');
    }
  });

  test('should have all main navigation items after login', async ({ page }) => {
    // 登录
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();

    // 等待仪表盘加载
    await expect(page).toHaveURL(/.*dashboard/);
    await expect(page.locator('.el-menu')).toBeVisible();

    // 检查主要菜单项是否存在
    const mainMenuItems = [
      '仪表盘',
      '项目管理',
      '数据源',
      '扫描管理',
      '图谱',
      '审核',
      '事实',
      '测试',
      '迁移风险',
      '系统管理'
    ];

    for (const item of mainMenuItems) {
      await expect(page.getByText(item).first()).toBeVisible();
    }

    console.log('✓ 所有主菜单项加载成功');
  });

  test('should navigate through main sections without errors', async ({ page }) => {
    // 登录
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);

    // 依次访问主要页面
    const routes = [
      '/projects',
      '/sources/repos',
      '/scan',
      '/graph/unified',
      '/facts',
      '/test-cases',
      '/risks'
    ];

    for (const route of routes) {
      await page.goto(route);
      await page.waitForLoadState('networkidle');

      // 检查没有错误页面显示
      await expect(page.locator('.el-page-404')).not.toBeVisible();
      console.log(`✓ ${route} 加载成功`);
    }
  });
});
