import { test, expect } from '@playwright/test';

test.describe('登录流程', () => {
  test('should display login page', async ({ page }) => {
    await page.goto('/login');

    // 检查登录表单是否显示
    await expect(page.getByLabel('用户名')).toBeVisible();
    await expect(page.getByLabel('密码')).toBeVisible();
    await expect(page.getByRole('button', { name: '登录' })).toBeVisible();
  });

  test('should login successfully with correct credentials', async ({ page }) => {
    await page.goto('/login');

    // 输入用户名和密码
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');

    // 点击登录按钮
    await page.getByRole('button', { name: '登录' }).click();

    // 验证登录成功后跳转到仪表盘
    await expect(page).toHaveURL(/.*dashboard/);
    await expect(page.locator('.el-menu')).toBeVisible();
  });

  test('should show error message with wrong password', async ({ page }) => {
    await page.goto('/login');

    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('wrongpassword');
    await page.getByRole('button', { name: '登录' }).click();

    // 验证错误消息显示
    await expect(page.locator('.el-message--error')).toBeVisible();
  });

  test('should logout successfully', async ({ page }) => {
    // 先登录
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();

    // 等待仪表盘加载
    await expect(page).toHaveURL(/.*dashboard/);

    // 点击退出登录（通常在用户下拉菜单中）
    await page.locator('.user-dropdown').click();
    await page.getByText('退出登录').click();

    // 验证回到登录页
    await expect(page).toHaveURL(/.*login/);
  });
});
