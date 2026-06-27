import { test, expect } from '@playwright/test';

test.describe('系统管理页面', () => {
  test.beforeEach(async ({ page }) => {
    // 先登录
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test.describe('用户管理', () => {
    test('should navigate to user list', async ({ page }) => {
      await page.getByText('系统管理').click();
      await page.getByText('用户管理').click();

      await expect(page).toHaveURL(/.*system\/users/);
      await expect(page.getByRole('heading', { name: '用户管理' })).toBeVisible();
    });

    test('should display user table', async ({ page }) => {
      await page.goto('/system/users');

      await expect(page.locator('.el-table')).toBeVisible();
      await expect(page.getByRole('button', { name: '新增用户' })).toBeVisible();
    });

    test('should search user by keyword', async ({ page }) => {
      await page.goto('/system/users');

      const searchInput = page.getByPlaceholder('搜索用户名');
      await expect(searchInput).toBeVisible();
      await searchInput.fill('admin');
      await expect(searchInput).toHaveValue('admin');
    });
  });

  test.describe('字典管理', () => {
    test('should navigate to dictionary list', async ({ page }) => {
      await page.getByText('系统管理').click();
      await page.getByText('字典管理').click();

      await expect(page).toHaveURL(/.*system\/dictionary/);
      await expect(page.getByRole('heading', { name: '字典管理' })).toBeVisible();
    });
  });

  test.describe('审计日志', () => {
    test('should navigate to audit log list', async ({ page }) => {
      await page.getByText('系统管理').click();
      await page.getByText('操作日志').click();

      await expect(page).toHaveURL(/.*system\/audit/);
      await expect(page.getByRole('heading', { name: '操作审计日志' })).toBeVisible();
    });

    test('should display audit log with filters', async ({ page }) => {
      await page.goto('/system/audit');

      await expect(page.locator('.el-table')).toBeVisible();
      await expect(page.getByPlaceholder('操作人')).toBeVisible();
      await expect(page.getByText('操作类型')).toBeVisible();
    });
  });

  test.describe('系统设置', () => {
    test('should navigate to settings', async ({ page }) => {
      await page.getByText('系统管理').click();
      await page.getByText('系统设置').click();

      await expect(page).toHaveURL(/.*system\/settings/);
      await expect(page.getByRole('heading', { name: '系统设置' })).toBeVisible();
    });

    test('should display configuration form', async ({ page }) => {
      await page.goto('/system/settings');

      // 配置表单项应该存在
      await expect(page.locator('.el-form')).toBeVisible();
      await expect(page.getByRole('button', { name: '保存' })).toBeVisible();
    });
  });
});
