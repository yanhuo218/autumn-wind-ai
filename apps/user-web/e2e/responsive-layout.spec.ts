import { expect, test, type Page } from '@playwright/test';

async function createConversationWithTitle(page: Page, title: string) {
  const response = await page.request.post('/api/v1/conversations', { data: { title } });
  expect(response.ok()).toBe(true);
}

const viewports = [
  { width: 1440, height: 900 },
  { width: 1024, height: 768 },
  { width: 800, height: 900 },
  { width: 768, height: 1024 },
  { width: 390, height: 844 },
  { width: 360, height: 800 }
] as const;

for (const viewport of viewports) {
  test(`${viewport.width}x${viewport.height} 无横向溢出且断点布局正确`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto('/chat');
    await expect(page.getByTestId('chat-route')).toBeVisible();

    const hasNoHorizontalOverflow = await page.evaluate(
      () => document.documentElement.scrollWidth <= document.documentElement.clientWidth
    );
    expect(hasNoHorizontalOverflow).toBe(true);

    const mobile = viewport.width <= 1023;
    if (mobile) {
      await expect(page.getByTestId('app-shell-sidebar')).toBeHidden();
      await expect(page.getByRole('button', { name: '打开侧栏' })).toBeVisible();
    } else {
      await expect(page.getByTestId('app-shell-sidebar')).toBeVisible();
      await expect(page.getByRole('button', { name: '打开侧栏' })).toBeHidden();
    }
  });
}

test('移动端侧栏抽屉可打开、关闭并恢复触发按钮焦点', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/chat');

  const openButton = page.getByRole('button', { name: '打开侧栏' });
  await openButton.click();
  const dialog = page.getByRole('dialog', { name: '会话侧栏' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('button', { name: '新建会话' })).toBeVisible();

  await dialog.click();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(openButton).toBeFocused();

  await openButton.click();
  await expect(dialog).toBeVisible();
  await page.getByRole('button', { name: '关闭侧栏' }).click();
  await expect(dialog).toBeHidden();

  const hasNoHorizontalOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth <= document.documentElement.clientWidth
  );
  expect(hasNoHorizontalOverflow).toBe(true);
});

test('模型选择器提供唯一可访问入口并支持键盘打开', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/chat');

  const selectors = page.getByRole('combobox', { name: '选择模型' });
  await expect(selectors).toHaveCount(1);
  await selectors.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('option', { name: /秋风文本/ }).first()).toBeVisible();
  await page.keyboard.press('Escape');
});

test('移动端 Tab 顺序从侧栏入口依次到模型和消息输入', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/chat');

  const openButton = page.getByRole('button', { name: '打开侧栏' });
  await openButton.focus();
  await expect(openButton).toBeFocused();

  await page.keyboard.press('Tab');
  await expect(page.getByRole('combobox', { name: '选择模型' })).toBeFocused();

  await page.keyboard.press('Tab');
  await expect(page.getByRole('textbox', { name: '消息输入' })).toBeFocused();
});

test('长会话名称在桌面侧栏中实际省略且保留完整提示', async ({ page }) => {
  const title = `用于验证桌面侧栏文本省略的超长会话名称-${Date.now()}-${'很长'.repeat(20)}`;
  await createConversationWithTitle(page, title);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/chat');

  const titleElement = page.locator('.aw-conversation-sidebar__title', { hasText: title });
  await expect(titleElement).toBeVisible();
  await expect(titleElement).toHaveAttribute('title', title);

  const overflow = await titleElement.evaluate((element) => ({
    clientWidth: element.clientWidth,
    scrollWidth: element.scrollWidth,
    textOverflow: getComputedStyle(element).textOverflow
  }));
  expect(overflow.scrollWidth).toBeGreaterThan(overflow.clientWidth);
  expect(overflow.textOverflow).toBe('ellipsis');
});

test('reduced motion 下生成状态 marker 不运行动画', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/chat?scenario=slow');
  const input = page.getByRole('textbox', { name: '消息输入' });
  await input.fill('请验证 reduced motion。');
  await page.getByRole('button', { name: '发送' }).click();

  const marker = page.getByTestId('generation-state-rail-marker');
  await expect(marker).toBeVisible();
  await expect.poll(() => marker.evaluate((element) => getComputedStyle(element).animationName)).toBe('none');
  await page.getByRole('button', { name: '停止生成' }).click();
});
