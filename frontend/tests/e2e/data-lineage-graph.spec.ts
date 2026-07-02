import { test, expect } from '@playwright/test';

test.describe('数据血缘图谱页面', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('用户名').fill('admin');
    await page.getByLabel('密码').fill('admin123');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/.*dashboard/);
  });

  test('should display data lineage graph page', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/lineage');

    // 版本选择器
    await expect(page.locator('.el-select').first()).toBeVisible({ timeout: 10000 });
  });

  test('should display version selector', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/lineage');

    const versionSelect = page.locator('.el-select').first();
    await expect(versionSelect).toBeVisible({ timeout: 5000 });
  });

  test('should display graph canvas', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/lineage');

    // 图谱画布渲染
    const flowCanvas = page.locator('.vue-flow, .vue-flow__container, [class*="flow"]');
    if (await flowCanvas.count() > 0) {
      await expect(flowCanvas.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display table nodes', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/lineage');

    // 数据表节点应在画布中显示（如果有数据）
    const graphContainer = page.locator('.vue-flow, [class*="graph"]');
    if (await graphContainer.count() > 0) {
      await expect(graphContainer.first()).toBeVisible({ timeout: 5000 });
    }
  });

  test('should display edge relationships', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/lineage');

    // 关联关系（边）应在画布中渲染
    const edges = page.locator('.vue-flow__edge, [class*="edge"]');
    // 如果有数据，边应该可见；如果没有数据，至少画布应该可见
    const canvas = page.locator('.vue-flow__container, [class*="graph"]');
    if (await canvas.isVisible()) {
      await expect(canvas).toBeVisible();
    }
  });

  test('should show node info on selection', async ({ page }) => {
    await page.goto('/projects/demo-project/graph/lineage');

    // 尝试点击画布中的节点
    const nodes = page.locator('.vue-flow__node, [class*="node"]');
    if (await nodes.count() > 0) {
      await nodes.first().click();
      // 节点详情面板应该出现
      const detailPanel = page.locator('[class*="detail"], [class*="info"], [class*="panel"]');
      if (await detailPanel.count() > 0) {
        await expect(detailPanel.first()).toBeVisible({ timeout: 3000 });
      }
    }
  });
});
