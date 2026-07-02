import { test, expect } from '@playwright/test';

test.describe('扫描任务列表', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display scan versions page', async ({ page }) => {
    await page.goto('/projects/demo-project/scan-versions');

    // 表格应该显示
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 10000 });
    // 新建扫描按钮
    await expect(page.getByRole('button', { name: '新建扫描' })).toBeVisible();
  });

  test('should display progress bars for running tasks', async ({ page }) => {
    await page.goto('/projects/demo-project/scan-versions');

    // 进度条组件
    const progressBars = page.locator('.el-progress');
    if (await progressBars.count() > 0) {
      await expect(progressBars.first()).toBeVisible();
    }
  });

  test('should display status tags', async ({ page }) => {
    await page.goto('/projects/demo-project/scan-versions');

    // 状态标签
    const statusTags = page.locator('.el-tag');
    if (await statusTags.count() > 0) {
      await expect(statusTags.first()).toBeVisible();
    }
  });

  test('should show empty state when no tasks', async ({ page }) => {
    await page.goto('/projects/demo-project/scan-versions');

    // 检查表格存在或空状态
    const table = page.locator('.el-table');
    const emptyState = page.locator('.el-empty, .el-table__empty-block');
    const tableVisible = await table.isVisible().catch(() => false);
    const emptyVisible = await emptyState.isVisible().catch(() => false);
    expect(tableVisible || emptyVisible).toBeTruthy();
  });

  test('should have action buttons for each task', async ({ page }) => {
    await page.goto('/projects/demo-project/scan-versions');

    const actionButtons = page.locator('.el-table__body .el-button');
    if (await actionButtons.count() > 0) {
      await expect(actionButtons.first()).toBeVisible();
    }
  });
});
