import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { Composer } from './composer';

describe('Composer', () => {
  it('空文本或未选模型时禁用发送', () => {
    const { rerender } = render(<Composer modelId="" onSubmit={vi.fn()} />);
    expect(screen.getByRole('button', { name: '发送' }).hasAttribute('disabled')).toBe(true);

    rerender(<Composer modelId="mock-model" onSubmit={vi.fn()} />);
    expect(screen.getByRole('button', { name: '发送' }).hasAttribute('disabled')).toBe(true);
  });

  it('Enter 发送，Shift+Enter 换行，输入法组合期间不发送', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(async () => undefined);
    render(<Composer modelId="mock-model" onSubmit={onSubmit} />);
    const textbox = screen.getByRole('textbox', { name: '消息输入' }) as HTMLTextAreaElement;

    await user.type(textbox, '第一行');
    await user.keyboard('{Shift>}{Enter}{/Shift}第二行');
    expect(textbox.value).toContain('第一行\n第二行');

    await user.keyboard('{Enter}');
    expect(onSubmit).toHaveBeenCalledWith({ text: '第一行\n第二行', modelId: 'mock-model' });

    onSubmit.mockClear();
    await user.type(textbox, '组合输入');
    textbox.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, isComposing: true }));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('生成中以相同尺寸位置显示停止按钮并阻止重复提交', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(async () => undefined);
    const onStop = vi.fn(async () => undefined);
    render(<Composer modelId="mock-model" submitting text="已有文本" onSubmit={onSubmit} onStop={onStop} />);

    const stop = screen.getByRole('button', { name: '停止' });
    expect(stop.classList.contains('aw-composer__action')).toBe(true);
    expect(screen.queryByRole('button', { name: '发送' })).toBeNull();
    await user.click(stop);
    expect(onStop).toHaveBeenCalledOnce();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('第一版不渲染附件控件', () => {
    render(<Composer modelId="mock-model" onSubmit={vi.fn()} />);

    expect(screen.queryByRole('button', { name: /附件|图片|文件/ })).toBeNull();
  });
});
