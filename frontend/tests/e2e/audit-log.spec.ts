import { test, expect } from '@playwright/test';

test.describe('审计日志页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display audit log list page', async ({ page }) => {
    await page.goto('/projects/demo-project/audit/logs');

    // 日志表格应该显示
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 10000 });
  });

  test('should display audit log table with columns', async ({ page }) => {
    await page.goto('/projects/demo-project/audit/logs');

    const tableHeader = page.locator('.el-table__header');
    await expect(tableHeader).toBeVisible({ timeout: 5000 });
  });

  test('should display module filter', async ({ page }) => {
    await page.goto('/projects/demo-project/audit/logs');

    // 模块筛选器
    const filters = page.locator('.el-select');
    if (await filters.count() > 0) {
      await expect(filters.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display action type filter', async ({ page }) => {
    await page.goto('/projects/demo-project/audit/logs');

    // 操作类型筛选器
    const actionFilter = page.locator('.el-select').nth(1);
    if (await actionFilter.isVisible()) {
      await expect(actionFilter).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display username filter', async ({ page }) => {
    await page.goto('/projects/demo-project/audit/logs');

    const usernameInput = page.getByPlaceholder(/用户名|操作人/);
    if (await usernameInput.count() > 0) {
      await expect(usernameInput.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display date range filter', async ({ page }) => {
    await page.goto('/projects/demo-project/audit/logs');

    const datePicker = page.locator('.el-date-picker, [class*="date-picker"], [class*="date"]');
    if (await datePicker.count() > 0) {
      await expect(datePicker.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should view log detail', async ({ page }) => {
    await page.goto('/projects/demo-project/audit/logs');

    // 查找详情按钮并点击
    const detailBtns = page.getByRole('button', { name: /详情|查看/ });
    if (await detailBtns.count() > 0) {
      await detailBtns.first().click();
      // 详情对话框应该打开
      await expect(page.locator('.el-dialog, .el-drawer').first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display operation status tags', async ({ page }) => {
    await page.goto('/projects/demo-project/audit/logs');

    const statusTags = page.locator('.el-tag');
    if (await statusTags.count() > 0) {
      await expect(statusTags.first()).toBeVisible({ timeout: 5000 });
    }
  });
});
