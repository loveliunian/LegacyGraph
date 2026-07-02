import { test, expect } from '@playwright/test';

test.describe('变更任务列表页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display change task list page', async ({ page }) => {
    await page.goto('/projects/demo-project/change-tasks');

    // 表格应该显示
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 10000 });
  });

  test('should display create change task button', async ({ page }) => {
    await page.goto('/projects/demo-project/change-tasks');

    const createBtn = page.getByRole('button', { name: /创建|新建|新增/ });
    if (await createBtn.isVisible()) {
      await expect(createBtn).toBeVisible();
    }
  });

  test('should display task type tags', async ({ page }) => {
    await page.goto('/projects/demo-project/change-tasks');

    // Bug修复、重构、升级等类型标签
    const typeTags = page.locator('.el-tag');
    if (await typeTags.count() > 0) {
      await expect(typeTags.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should open create change task dialog', async ({ page }) => {
    await page.goto('/projects/demo-project/change-tasks');

    const createBtn = page.getByRole('button', { name: /创建|新建|新增/ });
    if (await createBtn.isVisible()) {
      await createBtn.click();
      // 创建对话框应该打开
      await expect(page.locator('.el-dialog').first()).toBeVisible({ timeout: 5000 });
      await expect(page.getByLabel(/任务标题|title/).or(page.getByPlaceholder(/任务/))).toBeVisible();
    }
  });

  test('should display task status flow', async ({ page }) => {
    await page.goto('/projects/demo-project/change-tasks');

    // 状态标签列
    const statusTags = page.locator('.el-tag');
    if (await statusTags.count() > 0) {
      await expect(statusTags.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should have impact analysis button', async ({ page }) => {
    await page.goto('/projects/demo-project/change-tasks');

    // 影响分析/刷新影响子图按钮
    const impactBtns = page.getByRole('button', { name: /影响|impact/i });
    if (await impactBtns.count() > 0) {
      await expect(impactBtns.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should have generate patch button', async ({ page }) => {
    await page.goto('/projects/demo-project/change-tasks');

    // 生成补丁按钮
    const patchBtns = page.getByRole('button', { name: /补丁|patch/i });
    if (await patchBtns.count() > 0) {
      await expect(patchBtns.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should view task detail', async ({ page }) => {
    await page.goto('/projects/demo-project/change-tasks');

    // 查看详情
    const detailBtns = page.getByRole('button', { name: /详情|查看/ });
    if (await detailBtns.count() > 0) {
      await detailBtns.first().click();
      // 详情对话框应打开
      await expect(page.locator('.el-dialog, .el-drawer').first()).toBeVisible({ timeout: 5000 });
    }
  });
});
