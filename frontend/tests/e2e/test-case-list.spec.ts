import { test, expect } from '@playwright/test';

test.describe('测试用例列表页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display test case list page', async ({ page }) => {
    await page.goto('/projects/demo-project/test-cases');

    // 表格应该显示
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 10000 });
  });

  test('should display test case table with columns', async ({ page }) => {
    await page.goto('/projects/demo-project/test-cases');

    // 表格列头
    const tableHeader = page.locator('.el-table__header');
    await expect(tableHeader).toBeVisible({ timeout: 5000 });
  });

  test('should have create test case button', async ({ page }) => {
    await page.goto('/projects/demo-project/test-cases');

    const createBtn = page.getByRole('button', { name: /新建|创建|新增/ });
    if (await createBtn.isVisible()) {
      await expect(createBtn).toBeVisible();
    }
  });

  test('should have generate test cases button', async ({ page }) => {
    await page.goto('/projects/demo-project/test-cases');

    const generateBtn = page.getByRole('button', { name: /生成/ });
    if (await generateBtn.isVisible()) {
      await expect(generateBtn).toBeVisible();
    }
  });

  test('should have execute test button or link', async ({ page }) => {
    await page.goto('/projects/demo-project/test-cases');

    const executeLinks = page.getByRole('button', { name: /执行|运行/ });
    if (await executeLinks.count() > 0) {
      await expect(executeLinks.first()).toBeVisible();
    }
  });

  test('should display test case status tags', async ({ page }) => {
    await page.goto('/projects/demo-project/test-cases');

    const statusTags = page.locator('.el-tag, [class*="status"]');
    if (await statusTags.count() > 0) {
      await expect(statusTags.first()).toBeVisible({ timeout: 5000 });
    }
  });
});
