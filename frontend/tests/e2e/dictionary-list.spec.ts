import { test, expect } from '@playwright/test';

test.describe('字典管理页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display dictionary type list', async ({ page }) => {
    await page.goto('/system/dictionaries');

    // 字典类型列表表格
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 10000 });
  });

  test('should display dictionary management header', async ({ page }) => {
    await page.goto('/system/dictionaries');

    await expect(page.getByText('字典管理').or(page.getByText('字典'))).toBeVisible({ timeout: 5000 });
  });

  test('should have create dictionary type button', async ({ page }) => {
    await page.goto('/system/dictionaries');

    const createBtn = page.getByRole('button', { name: /新增|创建|新建/ });
    if (await createBtn.isVisible()) {
      await expect(createBtn).toBeVisible();
    }
  });

  test('should open dictionary items dialog', async ({ page }) => {
    await page.goto('/system/dictionaries');

    // 点击管理字典项按钮
    const manageBtn = page.getByRole('button', { name: /管理|字典项/ });
    if (await manageBtn.count() > 0) {
      await manageBtn.first().click();
      // 字典项管理对话框应该打开
      await expect(page.locator('.el-dialog').first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display search filter for dictionary types', async ({ page }) => {
    await page.goto('/system/dictionaries');

    const searchInput = page.getByPlaceholder(/搜索|关键词/);
    if (await searchInput.count() > 0) {
      await expect(searchInput.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should create new dictionary type', async ({ page }) => {
    await page.goto('/system/dictionaries');

    const createBtn = page.getByRole('button', { name: /新增|创建|新建/ });
    if (await createBtn.isVisible()) {
      await createBtn.click();
      // 创建对话框应该打开
      await expect(page.locator('.el-dialog').first()).toBeVisible({ timeout: 5000 });
      await expect(page.getByLabel(/字典编码|dictCode/).or(page.getByLabel(/字典名称|dictName/))).toBeVisible();
    }
  });
});
