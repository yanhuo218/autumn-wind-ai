import { expect, test, type Page } from '@playwright/test';

const responseText = '这是来自 Conversation Mock 的响应。';
const correlationId = /关联 ID：[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/i;

async function createConversation(page: Page): Promise<string> {
  const response = await page.request.post('/api/v1/conversations', {
    data: { title: `E2E 会话 ${Date.now()}` }
  });
  expect(response.ok()).toBe(true);
  const body = await response.json() as { conversationId?: string };
  expect(body.conversationId).toMatch(/^[0-9a-f-]{36}$/i);
  return body.conversationId as string;
}

async function sendMessage(page: Page, text = '请完成一次端到端测试。'): Promise<void> {
  const input = page.getByRole('textbox', { name: '消息输入' });
  await input.fill(text);
  await page.getByRole('button', { name: '发送' }).click();
}

test.describe('聊天工作区关键流程', () => {
  test('可选择模型、新建会话并完成成功生成', async ({ page }) => {
    await page.goto('/chat');
    await expect(page.getByTestId('chat-route')).toBeVisible();

    await page.getByRole('combobox', { name: '选择模型' }).click();
    await expect(page.getByRole('option', { name: '秋风文本推理' })).toBeVisible();
    await page.getByRole('option', { name: '秋风文本推理' }).click();
    await expect(page.getByTestId('model-selector-label').first()).toHaveText('秋风文本推理');

    await page.getByRole('button', { name: '新建会话' }).click();
    await expect(page).toHaveURL(/\/chat\/[0-9a-f-]{36}$/i);
    await sendMessage(page);

    await expect(page.getByTestId('generation-state-rail-container')).toHaveAttribute(
      'data-status',
      'SUCCEEDED'
    );
    await expect(page.getByText(responseText)).toBeVisible();
    await expect(page.getByRole('button', { name: '新建会话' })).toBeVisible();
    await expect(page.getByRole('button', { name: '复制回答' })).toBeVisible();
    await expect(page.getByRole('button', { name: '发送' })).toBeVisible();
  });

  test('Shift+Enter 保留换行且 Enter 发送消息', async ({ page }) => {
    const conversationId = await createConversation(page);
    await page.goto(`/chat/${conversationId}`);

    const input = page.getByRole('textbox', { name: '消息输入' });
    await input.fill('第一行');
    await input.press('Shift+Enter');
    await input.pressSequentially('第二行');
    await expect(input).toHaveValue('第一行\n第二行');

    await input.press('Enter');
    await expect(page.getByTestId('generation-state-rail-container')).toHaveAttribute(
      'data-status',
      'SUCCEEDED'
    );
    await expect(page.locator('.aw-message-list__user').getByText('第一行\n第二行')).toBeVisible();
  });

  test('失败生成显示安全摘要、关联 ID并允许重新生成', async ({ page }) => {
    const conversationId = await createConversation(page);
    await page.goto(`/chat/${conversationId}?scenario=failed`);
    await sendMessage(page, '请模拟上游超时。');

    await expect(page.getByTestId('generation-state-rail-container')).toHaveAttribute(
      'data-status',
      'FAILED'
    );
    await expect(page.getByText('生成失败：Mock 场景模拟上游超时。')).toBeVisible();
    await expect(page.getByText(correlationId).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '重新生成' })).toBeVisible();
    await expect(page.getByText(/端点|api[_ -]?key|token|authorization/i)).toHaveCount(0);
  });

  test('中断生成显示中断状态并提供恢复操作', async ({ page }) => {
    const conversationId = await createConversation(page);
    await page.goto(`/chat/${conversationId}?scenario=interrupted`);
    await sendMessage(page, '请模拟连接中断。');

    await expect(page.getByTestId('generation-state-rail-container')).toHaveAttribute(
      'data-status',
      'INTERRUPTED'
    );
    await expect(page.getByText('生成中断')).toBeVisible();
    await expect(page.getByRole('button', { name: '重新生成' })).toBeVisible();
  });

  test('slow 生成可停止并重新生成', async ({ page }) => {
    const conversationId = await createConversation(page);
    await page.goto(`/chat/${conversationId}?scenario=slow`);
    await sendMessage(page, '请生成后停止。');

    await expect(page.getByTestId('generation-state-rail-container')).toHaveAttribute(
      'data-status',
      'STREAMING'
    );
    await page.getByRole('button', { name: '停止生成' }).click();
    await expect(page.getByTestId('generation-state-rail-container')).toHaveAttribute(
      'data-status',
      'STOPPED'
    );

    await page.getByRole('button', { name: '重新生成' }).click();
    await expect(page.getByTestId('generation-state-rail-container')).toHaveAttribute(
      'data-status',
      'SUCCEEDED',
      { timeout: 10_000 }
    );
    await expect(page.getByText(responseText)).toBeVisible();
  });

  test('断线后续传并最终完成生成', async ({ page }) => {
    const conversationId = await createConversation(page);
    await page.goto(`/chat/${conversationId}?scenario=disconnect-once`);
    await sendMessage(page, '请模拟一次断线续传。');

    await expect.poll(async () => page.getByTestId('generation-state-rail-container').getAttribute('data-status'))
      .toMatch(/RECONNECTING|SUCCEEDED/);
    await expect(page.getByTestId('generation-state-rail-container')).toHaveAttribute(
      'data-status',
      'SUCCEEDED',
      { timeout: 10_000 }
    );
    await expect(page.getByText(responseText)).toBeVisible();
  });

  test('replay reset 使用快照同步后完成生成', async ({ page }) => {
    const conversationId = await createConversation(page);
    await page.goto(`/chat/${conversationId}?scenario=replay-reset`);
    await sendMessage(page, '请模拟快照同步。');

    await expect(page.getByTestId('generation-state-rail-container')).toHaveAttribute(
      'data-status',
      'SUCCEEDED',
      { timeout: 10_000 }
    );
    await expect(page.getByText(responseText)).toBeVisible();
  });
});
