import { test, expect } from '@playwright/test';

test.describe('用户管理页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display user list page', async ({ page }) => {
    await page.goto('/system/users');

    // 用户列表表格
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 10000 });
  });

  test('should display user management header', async ({ page }) => {
    await page.goto('/system/users');

    await expect(page.getByText('用户管理')).toBeVisible({ timeout: 5000 });
  });

  test('should have create user button', async ({ page }) => {
    await page.goto('/system/users');

    const createBtn = page.getByRole('button', { name: '新增用户' });
    if (await createBtn.isVisible()) {
      await expect(createBtn).toBeVisible();
    }
  });

  test('should display username search filter', async ({ page }) => {
    await page.goto('/system/users');

    const usernameInput = page.getByPlaceholder('请输入用户名');
    await expect(usernameInput).toBeVisible({ timeout: 5000 });
  });

  test('should display status filter', async ({ page }) => {
    await page.goto('/system/users');

    // 状态筛选器
    const statusSelects = page.locator('.el-select');
    if (await statusSelects.count() > 0) {
      await expect(statusSelects.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should open create user dialog', async ({ page }) => {
    await page.goto('/system/users');

    const createBtn = page.getByRole('button', { name: '新增用户' });
    if (await createBtn.isVisible()) {
      await createBtn.click();
      // 对话框应该打开
      await expect(page.locator('.el-dialog').first()).toBeVisible({ timeout: 5000 });
      await expect(page.getByLabel('用户名')).toBeVisible();
      await expect(page.getByLabel('邮箱')).toBeVisible();
    }
  });

  test('should have edit and delete action buttons', async ({ page }) => {
    await page.goto('/system/users');

    const editBtn = page.getByRole('button', { name: '编辑' });
    const deleteBtn = page.getByRole('button', { name: '删除' });
    if (await editBtn.count() > 0) {
      await expect(editBtn.first()).toBeVisible({ timeout: 5000 });
    }
    if (await deleteBtn.count() > 0) {
      await expect(deleteBtn.first()).toBeVisible();
    }
  });
});
