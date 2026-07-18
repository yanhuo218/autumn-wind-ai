import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { GenerationActions } from './generation-actions';

const correlationId = '33333333-3333-4333-8333-333333333333';

describe('GenerationActions', () => {
  it.each(['FAILED', 'INTERRUPTED'] as const)(
    '为 %s 显示已脱敏的错误摘要、关联 ID 和可键盘触发的重新生成操作',
    async (status) => {
      const onRegenerate = vi.fn();
      const user = userEvent.setup();

      render(
        <GenerationActions
          error={{
            code: 'AW-CONVERSATION-DEPENDENCY-0001',
            message: '上游响应超时',
            correlationId
          }}
          onRegenerate={onRegenerate}
          status={status}
        />
      );

      expect(screen.getByText('上游响应超时')).toBeTruthy();
      expect(screen.getByText(`关联 ID：${correlationId}`)).toBeTruthy();

      const action = screen.getByRole('button', { name: '重新生成' });
      action.focus();
      await user.keyboard('{Enter}');
      expect(onRegenerate).toHaveBeenCalledOnce();
    }
  );

  it('为停止状态提供重新生成操作但不展示错误详情', () => {
    render(<GenerationActions status="STOPPED" onRegenerate={vi.fn()} />);

    expect(screen.getByRole('button', { name: '重新生成' })).toBeTruthy();
    expect(screen.queryByText(/关联 ID：/)).toBeNull();
  });

  it('不为非恢复状态渲染操作', () => {
    const { container } = render(
      <GenerationActions
        error={{
          code: 'AW-CONVERSATION-DEPENDENCY-0001',
          message: '上游响应超时',
          correlationId
        }}
        onRegenerate={vi.fn()}
        status="SUCCEEDED"
      />
    );

    expect(container.firstChild).toBeNull();
  });

  it('对敏感错误摘要使用公共回退文案，并隐藏非 UUID 关联 ID', () => {
    render(
      <GenerationActions
        error={{
          code: 'AW-CONVERSATION-INTERNAL-0001',
          message: 'Authorization: Bearer secret-token',
          correlationId: 'https://internal.example.test/request/secret'
        }}
        onRegenerate={vi.fn()}
        status="FAILED"
      />
    );

    expect(screen.getByText('生成失败')).toBeTruthy();
    expect(screen.queryByText(/关联 ID：/)).toBeNull();
    expect(screen.queryByText(/secret-token/)).toBeNull();
    expect(screen.queryByText(/internal\.example/)).toBeNull();
  });
});
