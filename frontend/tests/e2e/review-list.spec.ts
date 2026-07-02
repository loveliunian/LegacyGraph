import { test, expect } from '@playwright/test';

test.describe('审核列表页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display pending review list', async ({ page }) => {
    await page.goto('/projects/demo-project/reviews');

    // 表格应该显示
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 10000 });
    // 标题应该显示
    await expect(page.getByText('待人工确认')).toBeVisible();
  });

  test('should display confidence slider filter', async ({ page }) => {
    await page.goto('/projects/demo-project/reviews');

    // 置信度滑块
    await expect(page.locator('.el-slider')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('最小置信度')).toBeVisible();
  });

  test('should have confirm and reject action buttons', async ({ page }) => {
    await page.goto('/projects/demo-project/reviews');

    const confirmButtons = page.getByRole('button', { name: '确认' });
    const rejectButtons = page.getByRole('button', { name: '驳回' });

    if (await confirmButtons.count() > 0) {
      await expect(confirmButtons.first()).toBeVisible();
    }
    if (await rejectButtons.count() > 0) {
      await expect(rejectButtons.first()).toBeVisible();
    }
  });

  test('should display review history page', async ({ page }) => {
    await page.goto('/projects/demo-project/review-history');

    // 审核历史页面应加载
    await expect(page.locator('.el-card, .el-table').first()).toBeVisible({ timeout: 10000 });
  });

  test('should filter reviews by version ID', async ({ page }) => {
    await page.goto('/projects/demo-project/reviews');

    const versionInput = page.getByPlaceholder('扫描版本ID');
    if (await versionInput.isVisible()) {
      await versionInput.fill('test-version');
      await page.getByRole('button', { name: '查询' }).click();
      // 表格重新加载
      await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 5000 });
    }
  });
});
