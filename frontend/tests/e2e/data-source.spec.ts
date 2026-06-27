import { test, expect } from '@playwright/test';

test.describe('数据源管理', () => {
  test.beforeEach(async ({ page }) => {
    // 先登录
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test.describe('代码仓库', () => {
    test('should navigate to code repo list', async ({ page }) => {
      await page.getByText('数据源').click();
      await page.getByText('代码仓库').click();

      await expect(page).toHaveURL(/.*sources\/repos/);
      await expect(page.getByRole('heading', { name: '代码仓库' })).toBeVisible();
    });

    test('should display code repo table', async ({ page }) => {
      await page.goto('/sources/repos');

      await expect(page.locator('.el-table')).toBeVisible();
      await expect(page.getByRole('button', { name: '新增仓库' })).toBeVisible();
    });

    test('should open create code repo dialog', async ({ page }) => {
      await page.goto('/sources/repos');

      await page.getByRole('button', { name: '新增仓库' }).click();

      await expect(page.getByRole('dialog', { name: '新增代码仓库' })).toBeVisible();
      await expect(page.getByLabel('仓库名称')).toBeVisible();
      await expect(page.getByLabel('Git 地址')).toBeVisible();
    });
  });

  test.describe('数据库连接', () => {
    test('should navigate to database list', async ({ page }) => {
      await page.getByText('数据源').click();
      await page.getByText('数据库连接').click();

      await expect(page).toHaveURL(/.*sources\/databases/);
      await expect(page.getByRole('heading', { name: '数据库连接' })).toBeVisible();
    });

    test('should open create database dialog', async ({ page }) => {
      await page.goto('/sources/databases');

      await page.getByRole('button', { name: '新增连接' }).click();

      await expect(page.getByRole('dialog', { name: '新增数据库连接' })).toBeVisible();
      await expect(page.getByLabel('连接名称')).toBeVisible();
      await expect(page.getByLabel('数据库类型')).toBeVisible();
      await expect(page.getByLabel('主机地址')).toBeVisible();
    });
  });

  test.describe('文档管理', () => {
    test('should navigate to document list', async ({ page }) => {
      await page.getByText('数据源').click();
      await page.getByText('文档管理').click();

      await expect(page).toHaveURL(/.*sources\/documents/);
      await expect(page.getByRole('heading', { name: '文档管理' })).toBeVisible();
    });

    test('should display upload button', async ({ page }) => {
      await page.goto('/sources/documents');

      await expect(page.locator('.el-table')).toBeVisible();
      await expect(page.getByRole('button', { name: '上传文档' })).toBeVisible();
    });
  });
});
