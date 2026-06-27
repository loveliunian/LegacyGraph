import { test, expect } from '@playwright/test';

test.describe('迁移风险页面', () => {
  test.beforeEach(async ({ page }) => {
    // 先登录
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should navigate to risk list', async ({ page }) => {
    await page.getByText('迁移风险').click();

    await expect(page).toHaveURL(/.*risks/);
    await expect(page.getByRole('heading', { name: '迁移风险清单' })).toBeVisible();
  });

  test('should display risk table with filters', async ({ page }) => {
    await page.goto('/risks');

    await expect(page.locator('.el-table')).toBeVisible();

    // 检查筛选器
    await expect(page.getByPlaceholder('风险名称')).toBeVisible();
    await expect(page.locator('.el-select').filter({ hasText: '风险等级' })).toBeVisible();
  });

  test('should filter risks by level', async ({ page }) => {
    await page.goto('/risks');

    const levelFilter = page.locator('.el-select').filter({ hasText: '风险等级' });
    await levelFilter.click();

    // 检查风险等级选项
    await expect(page.getByText('高危')).toBeVisible();
    await expect(page.getByText('中危')).toBeVisible();
    await expect(page.getByText('低危')).toBeVisible();
  });

  test('should open risk detail dialog', async ({ page }) => {
    await page.goto('/risks');

    // 如果表格有数据，点击查看详情
    const firstRow = page.locator('.el-table__body tr').first();
    if (await firstRow.isVisible()) {
      const detailButton = firstRow.getByRole('button', { name: '详情' });
      if (await detailButton.isVisible()) {
        await detailButton.click();
        await expect(page.getByRole('dialog', { name: '风险详情' })).toBeVisible();
      }
    }
  });
});
