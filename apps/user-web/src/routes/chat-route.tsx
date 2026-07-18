import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router';

import type { ModelView } from '@autumn-wind/api-contracts';

import { AppShell } from '../components/app-shell';
import { createConversationClient, type ConversationClient } from '../features/conversation/api/conversation-client';
import { ConversationSidebar } from '../features/conversation/components/conversation-sidebar';
import { createModelCatalog, getDefaultModelId, type ModelCatalog } from '../features/models/model-catalog';
import { ModelSelector } from '../features/models/components/model-selector';
import { conversationKeys, queryClient } from '../lib/query-client';

const defaultConversationClient = createConversationClient();
const defaultModelCatalog = createModelCatalog(
  import.meta.env.PROD || import.meta.env.VITE_MODEL_CATALOG_MODE === 'http' ? 'http' : 'mock'
);

export interface ChatRouteProps {
  conversationClient?: ConversationClient;
  modelCatalog?: ModelCatalog;
}

function ConversationTitle({ title }: { title: string }) {
  return (
    <span className="aw-chat-route__title" title={title}>
      {title}
    </span>
  );
}

function EmptyConversationState({ hasConversation }: { hasConversation: boolean }) {
  return (
    <div className="aw-chat-route__empty" data-testid="chat-empty-state">
      <strong>{hasConversation ? '选择模型后开始生成' : '新建会话后开始生成'}</strong>
    </div>
  );
}

export function ChatRoute({
  conversationClient = defaultConversationClient,
  modelCatalog = defaultModelCatalog
}: ChatRouteProps) {
  const navigate = useNavigate();
  const { conversationId } = useParams<{ conversationId?: string }>();
  const [selectedModelId, setSelectedModelId] = useState<string>();

  const conversationsQuery = useQuery({
    queryKey: conversationKeys.all,
    queryFn: ({ signal }) => conversationClient.listConversations(signal)
  });
  const detailQuery = useQuery({
    queryKey: conversationKeys.detail(conversationId ?? ''),
    queryFn: ({ signal }) => conversationClient.getConversation(conversationId!, signal),
    enabled: Boolean(conversationId)
  });
  const modelsQuery = useQuery({
    queryKey: ['models', 'available-text'] as const,
    queryFn: ({ signal }) => modelCatalog.listAvailableTextModels(signal)
  });

  const models = modelsQuery.data ?? [];
  const selectedModel = useMemo(
    () => models.find((model) => model.id === selectedModelId),
    [models, selectedModelId]
  );

  useEffect(() => {
    if (!selectedModel && models.length > 0) {
      setSelectedModelId(getDefaultModelId(models));
    }
  }, [models, selectedModel]);

  const title = detailQuery.data?.title ?? '新会话';
  const conversations = conversationsQuery.data?.items ?? [];

  const handleCreateConversation = async () => {
    try {
      const conversation = await conversationClient.createConversation();
      await queryClient.invalidateQueries({ queryKey: conversationKeys.all });
      navigate(`/chat/${conversation.conversationId}`);
    } catch {
      return;
    }
  };

  const handleArchiveConversation = async (archivedId: string) => {
    try {
      await conversationClient.archiveConversation(archivedId);
      await queryClient.invalidateQueries({ queryKey: conversationKeys.all });
      if (archivedId === conversationId) {
        navigate('/chat');
      }
    } catch {
      return;
    }
  };

  const modelSelector = (
    <ModelSelector
      models={models}
      value={selectedModelId}
      onValueChange={setSelectedModelId}
      disabled={modelsQuery.isPending || modelsQuery.isError}
    />
  );

  return (
    <div className="aw-chat-route" data-testid="chat-route">
      <AppShell
        sidebar={
          <ConversationSidebar
            conversations={conversations}
            activeConversationId={conversationId}
            onSelectConversation={(id) => navigate(`/chat/${id}`)}
            onCreateConversation={() => void handleCreateConversation()}
            onArchiveConversation={handleArchiveConversation}
          />
        }
        header={
          <div className="aw-chat-route__desktop-header">
            <ConversationTitle title={title} />
            {modelSelector}
          </div>
        }
        mobileHeader={{
          title: <ConversationTitle title={title} />,
          model: modelSelector
        }}
        messages={
          <EmptyConversationState hasConversation={Boolean(conversationId || detailQuery.data)} />
        }
        composer={<span aria-hidden="true" />}
        messagesTestId="user-web-root"
      />
    </div>
  );
}
