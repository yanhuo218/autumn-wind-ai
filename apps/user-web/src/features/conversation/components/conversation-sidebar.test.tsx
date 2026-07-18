import type { ConversationView } from '@autumn-wind/api-contracts';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ConversationSidebar, groupConversations } from './conversation-sidebar';

const now = new Date('2026-07-19T12:00:00Z');

function conversation(id: string, title: string, createdAt: string, archived = false): ConversationView {
  return {
    conversationId: id,
    title,
    createdAt,
    archived
  };
}

const today = conversation(
  '00000000-0000-4000-8000-000000000301',
  '今天会话',
  '2026-07-19T08:00:00Z'
);
const week = conversation(
  '00000000-0000-4000-8000-000000000302',
  '过去会话',
  '2026-07-15T08:00:00Z'
);
const earlier = conversation(
  '00000000-0000-4000-8000-000000000303',
  '更早会话',
  '2026-06-30T08:00:00Z'
);

describe('ConversationSidebar', () => {
  it('按今天、过去 7 天和更早分组并排除已归档项', () => {
    const archived = conversation(
      '00000000-0000-4000-8000-000000000304',
      '已归档',
      '2026-07-19T09:00:00Z',
      true
    );
    const groups = groupConversations([earlier, archived, today, week], now);

    expect(groups.today.map((item) => item.title)).toEqual(['今天会话']);
    expect(groups.lastSevenDays.map((item) => item.title)).toEqual(['过去会话']);
    expect(groups.earlier.map((item) => item.title)).toEqual(['更早会话']);
    expect(groups.today.concat(groups.lastSevenDays, groups.earlier)).not.toContain(archived);
  });

  it('按实际时间排序而不是按带时区字符串排序', () => {
    const utcLater = conversation(
      '00000000-0000-4000-8000-000000000305',
      'UTC 后创建',
      '2026-07-19T08:30:00Z'
    );
    const offsetEarlier = conversation(
      '00000000-0000-4000-8000-000000000306',
      '偏移时区先创建',
      '2026-07-19T10:00:00+02:00'
    );

    expect(groupConversations([offsetEarlier, utcLater], now).today.map((item) => item.title)).toEqual([
      'UTC 后创建',
      '偏移时区先创建'
    ]);
  });

  it('渲染当前项语义、新建会话和归档动作', async () => {
    const user = userEvent.setup();
    const onCreateConversation = vi.fn();
    const onArchiveConversation = vi.fn(async () => undefined);
    render(
      <ConversationSidebar
        conversations={[today, week]}
        activeConversationId={today.conversationId}
        onCreateConversation={onCreateConversation}
        onArchiveConversation={onArchiveConversation}
        now={now}
      />
    );

    expect(screen.getByRole('button', { name: '新建会话' })).toBeTruthy();
    expect(screen.getByRole('button', { name: today.title }).getAttribute('aria-current')).toBe('page');
    await user.click(screen.getByRole('button', { name: '新建会话' }));
    await user.click(screen.getByRole('button', { name: `归档 ${today.title}` }));

    expect(onCreateConversation).toHaveBeenCalledOnce();
    expect(onArchiveConversation).toHaveBeenCalledWith(today.conversationId);
  });

  it('空列表保留新建入口而不展示功能介绍', () => {
    render(
      <ConversationSidebar
        conversations={[]}
        onCreateConversation={vi.fn()}
        onArchiveConversation={vi.fn()}
        now={now}
      />
    );

    expect(screen.getByRole('button', { name: '新建会话' })).toBeTruthy();
    expect(screen.queryByText(/功能|介绍|欢迎使用/)).toBeNull();
  });
});
