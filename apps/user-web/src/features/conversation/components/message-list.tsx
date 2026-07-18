import type { MessageContent, MessageView } from '@autumn-wind/api-contracts';
import { CircleAlert, Clipboard, RotateCcw, Square } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

import { IconButton } from '../../../components/icon-button';
import type { GenerationUiState } from '../state/generation-state';
import { GenerationStateRail, normalizePublicSummary } from './generation-state-rail';

export interface MessageListProps {
  messages: MessageView[];
  activeGeneration?: GenerationUiState;
  error?: { code: string; message: string; correlationId?: string };
  onCopy?: (text: string) => Promise<void> | void;
  onStop?: () => Promise<void> | void;
  onRegenerate?: (generationId: string) => Promise<void> | void;
}

function contentToText(content: MessageContent): string {
  return content.blocks
    .filter((block): block is Extract<MessageContent['blocks'][number], { type: 'text' }> => block.type === 'text')
    .map((block) => block.text)
    .join('\n');
}

const busyStatuses = new Set<GenerationUiState['status']>([
  'PENDING',
  'STREAMING',
  'SYNCING'
]);

export function MessageList({
  messages,
  activeGeneration,
  error,
  onCopy,
  onStop,
  onRegenerate
}: MessageListProps) {
  return (
    <div aria-label="消息列表" className="aw-message-list">
      {error ? (
        <div className="aw-message-list__error" role="alert">
          <CircleAlert aria-hidden="true" size={16} strokeWidth={1.8} />
          <strong>{normalizePublicSummary(error.message)}</strong>
          {error.correlationId ? <span>关联 ID：{error.correlationId}</span> : null}
        </div>
      ) : null}
      {messages.map((message) => {
        const isAssistant = message.role === 'ASSISTANT';
        const isActive = Boolean(
          isAssistant &&
            activeGeneration &&
            message.generationId === activeGeneration.generationId
        );
        const text = isActive && activeGeneration
          ? activeGeneration.content
          : contentToText(message.content);

        if (!isAssistant) {
          return (
            <article className="aw-message-list__user" key={message.messageId}>
              <p className="aw-message-list__text">{text}</p>
            </article>
          );
        }

        return (
          <article
            aria-busy={isActive && activeGeneration ? busyStatuses.has(activeGeneration.status) : false}
            className="aw-message-list__assistant"
            key={message.messageId}
          >
            {isActive && activeGeneration ? (
              <GenerationStateRail
                contentDelta={activeGeneration.content}
                errorSummary={activeGeneration.error?.message}
                status={activeGeneration.status}
              />
            ) : null}
            <div className="aw-message-list__markdown">
              <ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml>
                {text}
              </ReactMarkdown>
            </div>
            <div className="aw-message-list__actions">
              {onCopy ? (
                <IconButton
                  label="复制回答"
                  onClick={() => void onCopy(text)}
                >
                  <Clipboard size={16} strokeWidth={1.8} />
                </IconButton>
              ) : null}
              {isActive && activeGeneration && onStop && busyStatuses.has(activeGeneration.status) ? (
                <IconButton label="停止生成" onClick={() => void onStop()}>
                  <Square size={16} strokeWidth={1.8} />
                </IconButton>
              ) : null}
              {message.generationId && onRegenerate ? (
                <IconButton
                  label="重新生成"
                  onClick={() => void onRegenerate(message.generationId as string)}
                >
                  <RotateCcw size={16} strokeWidth={1.8} />
                </IconButton>
              ) : null}
            </div>
          </article>
        );
      })}
    </div>
  );
}
