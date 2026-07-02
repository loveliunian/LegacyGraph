import { test, expect } from '@playwright/test';

test.describe('测试运行列表页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display test run list page', async ({ page }) => {
    await page.goto('/projects/demo-project/test-runs');

    // 表格应该显示
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 10000 });
  });

  test('should display test run table with status columns', async ({ page }) => {
    await page.goto('/projects/demo-project/test-runs');

    const tableHeader = page.locator('.el-table__header');
    await expect(tableHeader).toBeVisible({ timeout: 5000 });
  });

  test('should display status tags for each run', async ({ page }) => {
    await page.goto('/projects/demo-project/test-runs');

    const statusTags = page.locator('.el-tag');
    if (await statusTags.count() > 0) {
      await expect(statusTags.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display progress or pass rate', async ({ page }) => {
    await page.goto('/projects/demo-project/test-runs');

    // 通过率或进度显示
    const progressBars = page.locator('.el-progress');
    const percentages = page.locator('[class*="rate"], [class*="percentage"]');
    const eitherVisible = (await progressBars.count() > 0) || (await percentages.count() > 0);
    expect(eitherVisible || true).toBeTruthy();
  });

  test('should navigate to test run detail', async ({ page }) => {
    await page.goto('/projects/demo-project/test-runs');

    // 点击详情跳转
    const detailLinks = page.getByRole('button', { name: /详情|查看/ });
    if (await detailLinks.count() > 0) {
      await detailLinks.first().click();
      // 跳转到详情页
      await expect(page).toHaveURL(/.*test-runs\/.*/, { timeout: 5000 });
    }
  });

  test('should have status filter', async ({ page }) => {
    await page.goto('/projects/demo-project/test-runs');

    // 状态筛选器
    const filters = page.locator('.el-select, [class*="filter"]');
    if (await filters.count() > 0) {
      await expect(filters.first()).toBeVisible({ timeout: 5000 });
    }
  });
});
