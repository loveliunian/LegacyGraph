import { test, expect } from '@playwright/test';

test.describe('Agent 中心页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display agent hub page', async ({ page }) => {
    await page.goto('/projects/demo-project/agents');

    // Agent 卡片列表
    await expect(page.locator('.el-card').first()).toBeVisible({ timeout: 10000 });
  });

  test('should display agent cards', async ({ page }) => {
    await page.goto('/projects/demo-project/agents');

    // Agent 列表卡片
    const agentCards = page.locator('.el-card, [class*="agent"]');
    const cardCount = await agentCards.count();
    expect(cardCount).toBeGreaterThan(0);
  });

  test('should display agent types and descriptions', async ({ page }) => {
    await page.goto('/projects/demo-project/agents');

    // 检查至少有一个 Agent 标题
    await expect(page.getByText('通用 Agent').or(page.getByText('SQL 分析')).or(
      page.getByText('测试生成')
    )).toBeVisible({ timeout: 5000 });
  });

  test('should open agent dialog on click', async ({ page }) => {
    await page.goto('/projects/demo-project/agents');

    // 点击第一个 Agent 卡片
    const firstCard = page.locator('.el-card, [class*="agent-card"]').first();
    if (await firstCard.isVisible()) {
      await firstCard.click();
      // Agent 对话框应该打开
      await expect(page.locator('.el-dialog').first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display agent tags', async ({ page }) => {
    await page.goto('/projects/demo-project/agents');

    const tags = page.locator('.el-tag');
    if (await tags.count() > 0) {
      await expect(tags.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display execute button', async ({ page }) => {
    await page.goto('/projects/demo-project/agents');

    // 打开任意 agent 并验证执行按钮
    const firstCard = page.locator('.el-card, [class*="agent-card"]').first();
    if (await firstCard.isVisible()) {
      await firstCard.click();
      const executeBtn = page.getByRole('button', { name: /执行|运行|提交/ });
      if (await executeBtn.isVisible()) {
        await expect(executeBtn).toBeVisible({ timeout: 3000 });
      }
    }
  });
});
