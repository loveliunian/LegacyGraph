import { test, expect } from '@playwright/test';

test.describe('测试用例页面', () => {
  test.beforeEach(async ({ page }) => {
    // 先登录
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should navigate to test case list', async ({ page }) => {
    await page.getByText('测试').click();
    await page.getByText('测试用例').click();

    await expect(page).toHaveURL(/.*test-cases/);
    await expect(page.getByRole('heading', { name: '测试用例' })).toBeVisible();
  });

  test('should display test case table', async ({ page }) => {
    await page.goto('/test-cases');

    await expect(page.locator('.el-table')).toBeVisible();
    await expect(page.getByRole('button', { name: '生成测试' })).toBeVisible();
  });

  test('should navigate to test run list', async ({ page }) => {
    await page.getByText('测试').click();
    await page.getByText('测试运行').click();

    await expect(page).toHaveURL(/.*test-runs/);
    await expect(page.getByRole('heading', { name: '测试运行' })).toBeVisible();
  });

  test('should filter test cases by status', async ({ page }) => {
    await page.goto('/test-cases');

    const statusFilter = page.locator('.el-select').filter({ hasText: '状态' });
    await expect(statusFilter).toBeVisible();
    await statusFilter.click();

    await expect(page.getByText('通过')).toBeVisible();
    await expect(page.getByText('失败')).toBeVisible();
  });
});
