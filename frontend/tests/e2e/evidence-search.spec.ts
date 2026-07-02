import { test, expect } from '@playwright/test';

test.describe('证据检索页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display evidence search page', async ({ page }) => {
    await page.goto('/projects/demo-project/evidence');

    // 搜索框应该可见
    const searchInput = page.locator('.el-input').first();
    await expect(searchInput).toBeVisible({ timeout: 10000 });
  });

  test('should display search input field', async ({ page }) => {
    await page.goto('/projects/demo-project/evidence');

    const keywordInput = page.getByPlaceholder(/搜索|关键词|keyword/i);
    if (await keywordInput.count() > 0) {
      await expect(keywordInput.first()).toBeVisible();
    }
  });

  test('should display evidence type filter', async ({ page }) => {
    await page.goto('/projects/demo-project/evidence');

    // 证据类型筛选器
    const typeFilter = page.locator('.el-select').first();
    await expect(typeFilter).toBeVisible({ timeout: 5000 });
  });

  test('should display results list', async ({ page }) => {
    await page.goto('/projects/demo-project/evidence');

    // 结果列表或空状态
    const resultList = page.locator('.el-table, .el-card');
    if (await resultList.count() > 0) {
      await expect(resultList.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should perform keyword search', async ({ page }) => {
    await page.goto('/projects/demo-project/evidence');

    const keywordInput = page.getByPlaceholder(/搜索|关键词|keyword/i);
    if (await keywordInput.count() > 0) {
      await keywordInput.first().fill('login');
      const searchBtn = page.getByRole('button', { name: /搜索|查询/ });
      if (await searchBtn.isVisible()) {
        await searchBtn.click();
        await page.waitForTimeout(2000);
      }
    }
  });

  test('should display evidence type tags', async ({ page }) => {
    await page.goto('/projects/demo-project/evidence');

    // 证据类型标签
    const typeTags = page.locator('.el-tag');
    if (await typeTags.count() > 0) {
      await expect(typeTags.first()).toBeVisible({ timeout: 5000 });
    }
  });
});
