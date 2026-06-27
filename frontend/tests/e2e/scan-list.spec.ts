import { test, expect } from '@playwright/test';

test.describe('扫描任务列表页面', () => {
  test.beforeEach(async ({ page }) => {
    // 先登录
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should navigate to scan task list', async ({ page }) => {
    await page.getByText('扫描管理').click();
    await page.getByText('扫描任务').click();

    await expect(page).toHaveURL(/.*scan/);
    await expect(page.getByRole('heading', { name: '扫描版本' })).toBeVisible();
  });

  test('should display scan versions table', async ({ page }) => {
    await page.goto('/scan');

    await expect(page.locator('.el-table')).toBeVisible();
    await expect(page.getByRole('button', { name: '新建扫描' })).toBeVisible();
  });

  test('should open create scan dialog', async ({ page }) => {
    await page.goto('/scan');

    await page.getByRole('button', { name: '新建扫描' }).click();

    await expect(page.getByRole('dialog', { name: '新建扫描版本' })).toBeVisible();
    await expect(page.getByLabel('版本名称')).toBeVisible();
  });

  test('should filter scans by status', async ({ page }) => {
    await page.goto('/scan');

    const statusFilter = page.locator('.el-select').first();
    await expect(statusFilter).toBeVisible();
    await statusFilter.click();

    // 下拉选项应该显示
    await expect(page.getByText('待执行')).toBeVisible();
  });
});
