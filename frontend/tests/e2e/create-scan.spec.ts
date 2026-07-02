import { test, expect } from '@playwright/test';

test.describe('创建扫描页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should navigate to create scan page', async ({ page }) => {
    await page.goto('/projects/demo-project/scan-versions/create');

    // 步骤条应该显示
    await expect(page.locator('.el-steps')).toBeVisible({ timeout: 10000 });
  });

  test('should display step indicator', async ({ page }) => {
    await page.goto('/projects/demo-project/scan-versions/create');

    // 步骤条应该有多个步骤
    const steps = page.locator('.el-step__title');
    const stepCount = await steps.count();
    expect(stepCount).toBeGreaterThan(0);
  });

  test('should display scan scope selection (step 1)', async ({ page }) => {
    await page.goto('/projects/demo-project/scan-versions/create');

    // 第一步：选择扫描范围（代码仓库、数据库、文档）
    await expect(page.getByText('选择扫描范围').or(page.getByText('扫描范围'))).toBeVisible({ timeout: 10000 });

    // 数据源列表应显示
    const checkboxes = page.locator('.el-checkbox');
    if (await checkboxes.count() > 0) {
      await expect(checkboxes.first()).toBeVisible();
    }
  });

  test('should navigate to scan type selection (step 2)', async ({ page }) => {
    await page.goto('/projects/demo-project/scan-versions/create');

    // 点击下一步
    const nextButton = page.getByRole('button', { name: '下一步' });
    if (await nextButton.isVisible() && await nextButton.isEnabled()) {
      await nextButton.click();
      // 扫描类型选择应该显示
      await expect(page.getByText('选择扫描类型').or(page.getByText('扫描类型'))).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display configuration form (step 3)', async ({ page }) => {
    await page.goto('/projects/demo-project/scan-versions/create');

    // 尝试快速跳转到配置步骤
    const nextButton = page.getByRole('button', { name: '下一步' });
    // 步骤 2
    if (await nextButton.isVisible()) {
      // 选中一个扫描类型
      const scanTypeCheckbox = page.locator('.el-checkbox').first();
      if (await scanTypeCheckbox.count() > 0) {
        await scanTypeCheckbox.click();
      }
      if (await nextButton.isEnabled()) await nextButton.click();
    }
    // 步骤 3
    if (await nextButton.isVisible() && await nextButton.isEnabled()) {
      await nextButton.click();
    }

    // 配置表单应显示
    await expect(page.getByText('配置参数').or(page.getByText('任务名称'))).toBeVisible({ timeout: 5000 });
  });
});
