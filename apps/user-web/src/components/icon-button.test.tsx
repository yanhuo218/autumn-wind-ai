import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it } from 'vitest';
import { Plus } from 'lucide-react';

import { IconButton } from './icon-button';

it('使用 label 作为按钮可访问名称并在获得焦点时显示 Tooltip', async () => {
  const user = userEvent.setup();

  render(
    <IconButton label="新建会话">
      <Plus aria-hidden="true" />
    </IconButton>
  );

  const button = screen.getByRole('button', { name: '新建会话' });
  expect(button.getAttribute('aria-label')).toBe('新建会话');
  expect(button.hasAttribute('data-icon-button')).toBe(true);

  await user.hover(button);
  expect((await screen.findByRole('tooltip')).textContent).toContain('新建会话');
});

it('保留固定控件尺寸标记，状态文字变化不会改变按钮布局', () => {
  render(
    <IconButton label="停止生成" data-testid="stop-button">
      <span aria-hidden="true">■</span>
    </IconButton>
  );

  const button = screen.getByTestId('stop-button');
  expect(button.className).toContain('aw-icon-button');
  expect(button.getAttribute('type')).toBe('button');
});
