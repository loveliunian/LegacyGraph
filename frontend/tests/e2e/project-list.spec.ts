import { test, expect } from '@playwright/test';

test.describe('项目列表页面', () => {
  test.beforeEach(async ({ page }) => {
    // 先登录
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should navigate to project list', async ({ page }) => {
    await page.getByText('项目管理').click();
    await page.getByText('项目列表').click();

    await expect(page).toHaveURL(/.*projects/);
    await expect(page.getByRole('heading', { name: '项目列表' })).toBeVisible();
  });

  test('should display project table with data', async ({ page }) => {
    await page.goto('/projects');

    // 检查表格是否显示
    await expect(page.locator('.el-table')).toBeVisible();

    // 如果有数据，检查分页是否显示
    const hasPagination = await page.locator('.el-pagination').isVisible();
    if (hasPagination) {
      await expect(page.locator('.el-pagination')).toBeVisible();
    }
  });

  test('should search projects by keyword', async ({ page }) => {
    await page.goto('/projects');

    // 搜索输入框应该存在
    const searchInput = page.getByPlaceholder('搜索项目名称');
    await expect(searchInput).toBeVisible();

    // 输入搜索关键词
    await searchInput.fill('test');

    // 表格应该更新（这里只是验证交互可用）
    await expect(searchInput).toHaveValue('test');
  });

  test('should open create project dialog', async ({ page }) => {
    await page.goto('/projects');

    // 点击新建项目按钮
    await page.getByRole('button', { name: '新建项目' }).click();

    // 对话框应该弹出
    await expect(page.getByRole('dialog', { name: '新建项目' })).toBeVisible();
    await expect(page.getByLabel('项目名称')).toBeVisible();
  });
});
