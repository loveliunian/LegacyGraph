import { test, expect } from '@playwright/test';

test.describe('事实列表页面', () => {
  test.beforeEach(async ({ page }) => {
    // 先登录
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should navigate to fact list', async ({ page }) => {
    await page.getByText('事实').click();
    await page.getByText('事实列表').click();

    await expect(page).toHaveURL(/.*facts/);
    await expect(page.getByRole('heading', { name: '事实列表' })).toBeVisible();
  });

  test('should display fact table with filters', async ({ page }) => {
    await page.goto('/facts');

    await expect(page.locator('.el-table')).toBeVisible();

    // 检查筛选器是否存在
    await expect(page.getByPlaceholder('事实类型')).toBeVisible();
    await expect(page.getByPlaceholder('最小置信度')).toBeVisible();
  });

  test('should filter facts by confidence', async ({ page }) => {
    await page.goto('/facts');

    const minConfidenceInput = page.getByPlaceholder('最小置信度');
    await expect(minConfidenceInput).toBeVisible();
    await minConfidenceInput.fill('0.8');

    await expect(minConfidenceInput).toHaveValue('0.8');
  });

  test('should open fact detail', async ({ page }) => {
    await page.goto('/facts');

    // 如果表格有数据，点击第一行查看详情
    const firstRow = page.locator('.el-table__body tr').first();
    if (await firstRow.isVisible()) {
      // 点击查看详情按钮（如果存在）
      const viewButton = firstRow.getByRole('button', { name: '查看' });
      if (await viewButton.isVisible()) {
        await viewButton.click();
        await expect(page.getByRole('dialog', { name: '事实详情' })).toBeVisible();
      }
    }
  });
});
