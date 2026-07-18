import { MoreHorizontal, Plus } from 'lucide-react';

import type { ConversationView } from '@autumn-wind/api-contracts';

import { IconButton } from '../../../components/icon-button';

export interface ConversationGroups {
  today: ConversationView[];
  lastSevenDays: ConversationView[];
  earlier: ConversationView[];
}

export interface ConversationSidebarProps {
  conversations: ConversationView[];
  activeConversationId?: string;
  onSelectConversation?: (conversationId: string) => void;
  onCreateConversation: () => void;
  onArchiveConversation: (conversationId: string) => Promise<void> | void;
  now?: Date;
}

function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
}

function dayDifference(now: Date, createdAt: string): number {
  const difference = startOfDay(now) - startOfDay(new Date(createdAt));
  return Math.floor(difference / 86_400_000);
}

export function groupConversations(conversations: ConversationView[], now = new Date()): ConversationGroups {
  const groups: ConversationGroups = { today: [], lastSevenDays: [], earlier: [] };

  for (const conversation of conversations) {
    if (conversation.archived) {
      continue;
    }

    const difference = dayDifference(now, conversation.createdAt);
    const group = difference <= 0 ? groups.today : difference <= 7 ? groups.lastSevenDays : groups.earlier;
    group.push(conversation);
  }

  const orderedGroups: ConversationView[][] = [groups.today, groups.lastSevenDays, groups.earlier];
  for (const group of orderedGroups) {
    group.sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
  }

  return groups;
}

function ConversationGroup({
  id,
  label,
  conversations,
  activeConversationId,
  onSelectConversation,
  onArchiveConversation
}: {
  id: string;
  label: string;
  conversations: ConversationView[];
  activeConversationId?: string;
  onSelectConversation?: (conversationId: string) => void;
  onArchiveConversation: (conversationId: string) => Promise<void> | void;
}) {
  if (conversations.length === 0) {
    return null;
  }

  return (
    <section className="aw-conversation-sidebar__group" aria-labelledby={`conversation-group-${id}`}>
      <h2 className="aw-conversation-sidebar__group-label" id={`conversation-group-${id}`}>
        {label}
      </h2>
      <ul className="aw-conversation-sidebar__list">
        {conversations.map((conversation) => {
          const active = conversation.conversationId === activeConversationId;
          return (
            <li className="aw-conversation-sidebar__item" key={conversation.conversationId}>
              <button
                className={`aw-conversation-sidebar__conversation${active ? ' is-active' : ''}`}
                type="button"
                aria-current={active ? 'page' : undefined}
                onClick={() => onSelectConversation?.(conversation.conversationId)}
              >
                <span className="aw-conversation-sidebar__title" title={conversation.title}>
                  {conversation.title}
                </span>
              </button>
              <IconButton
                className="aw-conversation-sidebar__archive"
                label={`归档 ${conversation.title}`}
                onClick={() => void onArchiveConversation(conversation.conversationId)}
              >
                <MoreHorizontal aria-hidden="true" size={17} strokeWidth={1.8} />
              </IconButton>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

export function ConversationSidebar({
  conversations,
  activeConversationId,
  onSelectConversation,
  onCreateConversation,
  onArchiveConversation,
  now
}: ConversationSidebarProps) {
  const groups = groupConversations(conversations, now);

  return (
    <aside className="aw-conversation-sidebar" aria-label="会话列表">
      <div className="aw-conversation-sidebar__topbar">
        <span className="aw-conversation-sidebar__brand">Autumn Wind Ai</span>
        <IconButton label="新建会话" onClick={onCreateConversation}>
          <Plus aria-hidden="true" size={18} strokeWidth={1.8} />
        </IconButton>
      </div>
      <nav className="aw-conversation-sidebar__groups" aria-label="会话分组">
        <ConversationGroup
          id="today"
          label="今天"
          conversations={groups.today}
          activeConversationId={activeConversationId}
          onSelectConversation={onSelectConversation}
          onArchiveConversation={onArchiveConversation}
        />
        <ConversationGroup
          id="last-seven-days"
          label="过去 7 天"
          conversations={groups.lastSevenDays}
          activeConversationId={activeConversationId}
          onSelectConversation={onSelectConversation}
          onArchiveConversation={onArchiveConversation}
        />
        <ConversationGroup
          id="earlier"
          label="更早"
          conversations={groups.earlier}
          activeConversationId={activeConversationId}
          onSelectConversation={onSelectConversation}
          onArchiveConversation={onArchiveConversation}
        />
      </nav>
    </aside>
  );
}
