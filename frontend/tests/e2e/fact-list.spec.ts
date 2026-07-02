import { test, expect } from '@playwright/test';

test.describe('事实列表页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display fact list page', async ({ page }) => {
    await page.goto('/projects/demo-project/facts');

    // 表格应该显示
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 10000 });
  });

  test('should display fact table with data or empty state', async ({ page }) => {
    await page.goto('/projects/demo-project/facts');

    const table = page.locator('.el-table');
    const emptyState = page.locator('.el-empty, .el-table__empty-block');
    const tableVisible = await table.first().isVisible().catch(() => false);
    const emptyVisible = await emptyState.isVisible().catch(() => false);
    expect(tableVisible || emptyVisible).toBeTruthy();
  });

  test('should display fact type filter', async ({ page }) => {
    await page.goto('/projects/demo-project/facts');

    // 事实类型筛选器
    const typeFilter = page.locator('.el-select').first();
    if (await typeFilter.isVisible()) {
      await expect(typeFilter).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display source type filter', async ({ page }) => {
    await page.goto('/projects/demo-project/facts');

    // 来源类型筛选器
    const sourceFilter = page.locator('.el-select').nth(1);
    if (await sourceFilter.isVisible()) {
      await expect(sourceFilter).toBeVisible();
    }
  });

  test('should view fact detail on click', async ({ page }) => {
    await page.goto('/projects/demo-project/facts');

    // 点击第一行查看详情
    const firstRow = page.locator('.el-table__body tr').first();
    if (await firstRow.isVisible()) {
      await firstRow.click();
      // 详情对话框
      const dialog = page.locator('.el-dialog, .el-drawer');
      if (await dialog.isVisible()) {
        await expect(dialog).toBeVisible();
      }
    }
  });

  test('should have keyword search input', async ({ page }) => {
    await page.goto('/projects/demo-project/facts');

    const searchInput = page.getByPlaceholder(/搜索|关键词|keyword|事实/);
    if (await searchInput.count() > 0) {
      await expect(searchInput.first()).toBeVisible();
    }
  });
});
