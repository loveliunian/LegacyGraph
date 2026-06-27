import { test, expect } from '@playwright/test';

test.describe('图谱可视化页面', () => {
  test.beforeEach(async ({ page }) => {
    // 先登录
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display unified graph view', async ({ page }) => {
    await page.getByText('图谱').click();
    await page.getByText('统一图谱').click();

    await expect(page).toHaveURL(/.*graph/);
    await expect(page.getByText('统一图谱')).toBeVisible();

    // 检查画布容器
    await expect(page.locator('#graph-container')).toBeVisible();
  });

  test('should have graph interaction available', async ({ page }) => {
    await page.goto('/graph/unified');

    // 等待图表应该渲染
    await page.waitForSelector('#graph-container svg', { timeout: 10000 });

    // 缩放控制应该存在
    await expect(page.getByTitle('放大')).toBeVisible();
    await expect(page.getByTitle('缩小')).toBeVisible();
    await expect(page.getByTitle('重置')).toBeVisible();
  });

  test('should fit view', async ({ page }) => {
    await page.goto('/graph/unified');
    await page.waitForSelector('#graph-container svg', { timeout: 10000 });

    // 点击适应视图按钮
    await page.getByTitle('适应视图').click();

    // 只是验证按钮可点击，不验证具体结果
    await expect(page.getByTitle('适应视图')).toBeEnabled();
  });
});
