import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it } from 'vitest';

import { AppShell } from './app-shell';

it('提供稳定的 sidebar、header、message 和 composer 四区', () => {
  render(
    <AppShell
      sidebar={<span>侧栏内容</span>}
      header={<span>顶栏内容</span>}
      messages={<p>消息内容</p>}
      composer={<textarea aria-label="输入消息" />}
    />
  );

  expect(screen.getByTestId('app-shell-sidebar').textContent).toContain('侧栏内容');
  expect(screen.getByTestId('app-shell-desktop-header').textContent).toContain('顶栏内容');
  expect(screen.getByTestId('app-shell-header').textContent).toContain('顶栏内容');
  expect(screen.getByTestId('app-shell-messages').textContent).toContain('消息内容');
  expect(screen.getByTestId('app-shell-composer').contains(
    screen.getByRole('textbox', { name: '输入消息' })
  )).toBe(true);
});

it('包含可触发的 Radix Dialog 移动侧栏抽屉并保持桌面侧栏 DOM', async () => {
  const user = userEvent.setup();

  render(
    <AppShell
      sidebar={<span>响应式侧栏</span>}
      header={<span>响应式顶栏</span>}
      messages={<p>响应式消息</p>}
      composer={<span>响应式输入区</span>}
    />
  );

  expect(screen.getByTestId('app-shell-sidebar')).toBeTruthy();
  const trigger = screen.getByRole('button', { name: '打开侧栏' });
  await user.click(trigger);

  expect((await screen.findByRole('dialog')).textContent).toContain('响应式侧栏');
  expect(screen.getByRole('dialog').hasAttribute('data-mobile-sidebar')).toBe(true);
});

it('为移动端渲染独立的标题行和模型行 slot', () => {
  render(
    <AppShell
      header={<span>桌面顶栏</span>}
      mobileHeader={{
        title: <span>移动会话标题</span>,
        model: <button aria-haspopup="listbox">移动模型菜单</button>,
        account: <button aria-label="账户菜单">账户</button>
      }}
    />
  );

  expect(screen.getByTestId('app-shell-mobile-header')).toBeTruthy();
  expect(screen.getByTestId('app-shell-mobile-title-row').textContent).toContain('移动会话标题');
  expect(screen.getByTestId('app-shell-mobile-model-row').textContent).toContain('移动模型菜单');
  expect(screen.getByTestId('app-shell-mobile-model-trigger').querySelector('[aria-haspopup="listbox"]')).toBeTruthy();
  expect(screen.getByTestId('app-shell-mobile-account').textContent).toContain('账户');
});

it('按 Escape 关闭抽屉并把焦点恢复到触发器', async () => {
  const user = userEvent.setup();
  render(<AppShell sidebar={<span>侧栏</span>} />);

  const trigger = screen.getByRole('button', { name: '打开侧栏' });
  await user.click(trigger);
  const dialog = await screen.findByRole('dialog');
  await user.click(dialog);
  await user.keyboard('{Escape}');

  expect(screen.queryByRole('dialog')).toBeNull();
  expect(document.activeElement).toBe(trigger);
});
