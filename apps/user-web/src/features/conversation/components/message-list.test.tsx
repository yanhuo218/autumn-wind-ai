import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { MessageView } from '@autumn-wind/api-contracts';

import type { GenerationUiState } from '../state/generation-state';
import { MessageList } from './message-list';

const userMessage: MessageView = {
  messageId: '11111111-1111-4111-8111-111111111111',
  role: 'USER',
  content: { schemaVersion: 1, blocks: [{ type: 'text', text: 'Mock user' }] },
  completeness: 'COMPLETE',
  generationId: null,
  createdAt: '2026-07-19T00:00:00.000Z'
};

const assistantMessage: MessageView = {
  messageId: '22222222-2222-4222-8222-222222222222',
  role: 'ASSISTANT',
  content: { schemaVersion: 1, blocks: [{ type: 'text', text: 'Mock answer' }] },
  completeness: 'COMPLETE',
  generationId: '33333333-3333-4333-8333-333333333333',
  createdAt: '2026-07-19T00:00:01.000Z'
};

const activeGeneration: GenerationUiState = {
  generationId: assistantMessage.generationId as string,
  status: 'STREAMING',
  content: 'Mock streamed **answer**\n\n- first\n- second\n\n| A | B |\n| - | - |\n| 1 | 2 |\n\n<script>window.bad = true</script>',
  reasoning: '',
  usage: { promptTokens: null, completionTokens: null, totalTokens: null },
  lastSequence: 2,
  seenEventIds: new Set()
};

describe('MessageList', () => {
  it('投影用户和助手文本，并显示活动生成状态栏', () => {
    render(<MessageList messages={[userMessage, assistantMessage]} activeGeneration={activeGeneration} />);

    expect(screen.getByText('Mock user')).toBeTruthy();
    expect(screen.getByText('Mock streamed')).toBeTruthy();
    expect(screen.getByTestId('generation-state-rail-container').getAttribute('data-status')).toBe('STREAMING');
  });

  it('使用 GFM Markdown，并跳过原始 HTML', () => {
    render(<MessageList messages={[assistantMessage]} activeGeneration={activeGeneration} />);

    expect(screen.getByText('answer').tagName).toBe('STRONG');
    expect(screen.getByRole('list')).toBeTruthy();
    expect(screen.getByRole('table')).toBeTruthy();
    expect(document.querySelector('script')).toBeNull();
  });

  it('提供复制、停止和重新生成图标按钮', () => {
    const onCopy = vi.fn();
    const onStop = vi.fn();
    const onRegenerate = vi.fn();
    render(
      <MessageList
        messages={[userMessage, assistantMessage]}
        activeGeneration={activeGeneration}
        onCopy={onCopy}
        onStop={onStop}
        onRegenerate={onRegenerate}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: '复制回答' }));
    fireEvent.click(screen.getByRole('button', { name: '停止生成' }));
    fireEvent.click(screen.getByRole('button', { name: '重新生成' }));

    expect(onCopy).toHaveBeenCalledWith(expect.stringContaining('Mock streamed'));
    expect(onStop).toHaveBeenCalledOnce();
    expect(onRegenerate).toHaveBeenCalledWith(assistantMessage.generationId);
  });
});
