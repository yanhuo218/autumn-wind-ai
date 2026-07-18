import type {
  ConversationStreamEventV1,
  ConversationView,
  GenerationAcceptedView,
  GenerationView
} from '@autumn-wind/api-contracts';
import { act, renderHook, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';

import type { ConversationClient } from '../api/conversation-client';
import { HttpError } from '../../../lib/http-error';
import { useConversationSession } from './use-conversation-session';

const conversationId = '00000000-0000-4000-8000-000000000201';
const generationId = '00000000-0000-4000-8000-000000000202';
const userMessageId = '00000000-0000-4000-8000-000000000203';
const clientRequestId = '00000000-0000-4000-8000-000000000204';
const modelId = '00000000-0000-4000-8000-000000000205';

function conversation(): ConversationView {
  return {
    conversationId,
    title: 'Mock title',
    createdAt: '2026-07-19T12:00:00Z',
    archived: false
  };
}

function accepted(overrides: Partial<GenerationAcceptedView> = {}): GenerationAcceptedView {
  return {
    userMessageId,
    generationId,
    statusUrl: `/api/v1/generations/${generationId}`,
    eventsUrl: `/api/v1/generations/${generationId}/events`,
    ...overrides
  };
}

function snapshot(overrides: Partial<GenerationView> = {}): GenerationView {
  return {
    generationId,
    conversationId,
    status: 'PENDING',
    content: { schemaVersion: 1, blocks: [{ type: 'text', text: '' }] },
    createdAt: '2026-07-19T12:00:00Z',
    updatedAt: '2026-07-19T12:00:00Z',
    ...overrides
  };
}

function event<T extends ConversationStreamEventV1['eventType']>(
  eventType: T,
  sequence: number,
  payload: Extract<ConversationStreamEventV1, { eventType: T }>['payload']
): Extract<ConversationStreamEventV1, { eventType: T }> {
  return {
    eventId: `event-${sequence}`,
    eventType,
    generationId,
    sequence,
    occurredAt: '2026-07-19T12:00:00Z',
    payloadVersion: 1,
    payload
  } as Extract<ConversationStreamEventV1, { eventType: T }>;
}

function client(overrides: Partial<ConversationClient> = {}): ConversationClient {
  return {
    listConversations: vi.fn(),
    getConversation: vi.fn(),
    createConversation: vi.fn(async () => conversation()),
    archiveConversation: vi.fn(),
    createGeneration: vi.fn(async () => accepted()),
    getGeneration: vi.fn(async () => snapshot()),
    streamGeneration: vi.fn(async function* () {}),
    stopGeneration: vi.fn(async () => snapshot({ status: 'STOPPED' })),
    regenerate: vi.fn(),
    ...overrides
  };
}

it('无会话时先创建会话，accepted 后进入 PENDING 并导航到新会话', async () => {
  vi.stubGlobal('crypto', { randomUUID: vi.fn(() => clientRequestId) });
  const createConversation = vi.fn(async () => conversation());
  const createGeneration = vi.fn(async (_id: string, _request: Parameters<ConversationClient['createGeneration']>[1]) => accepted());
  const onConversationCreated = vi.fn();
  const result = renderHook(() =>
    useConversationSession({
      conversationClient: client({ createConversation, createGeneration }),
      onConversationCreated
    })
  );

  await act(async () => {
    await result.result.current.submit({ text: 'Mock prompt', modelId });
  });

  expect(createConversation).toHaveBeenCalledOnce();
  expect(createGeneration).toHaveBeenCalledWith(
    conversationId,
    expect.objectContaining({
      clientRequestId,
      modelId,
      content: { schemaVersion: 1, blocks: [{ type: 'text', text: 'Mock prompt' }] }
    }),
    expect.anything()
  );
  expect(onConversationCreated).toHaveBeenCalledWith(conversationId);
  expect(result.result.current.activeGeneration).toMatchObject({
    generationId,
    status: 'PENDING'
  });
});

it('创建生成重试时复用同一个 clientRequestId，避免重复提交', async () => {
  vi.stubGlobal('crypto', { randomUUID: vi.fn(() => clientRequestId) });
  const requests: unknown[] = [];
  const createGeneration = vi.fn(async (_id: string, request: Parameters<ConversationClient['createGeneration']>[1]) => {
    requests.push(request);
    if (requests.length === 1) {
      throw new Error('temporary network failure');
    }
    return accepted();
  });
  const result = renderHook(() =>
    useConversationSession({ conversationClient: client({ createGeneration }), conversationId })
  );

  await act(async () => {
    await result.result.current.submit({ text: 'Retry once', modelId });
  });

  expect(createGeneration).toHaveBeenCalledTimes(2);
  expect(requests[0]).toEqual(requests[1]);
  expect(result.result.current.activeGeneration?.status).toBe('PENDING');
});

it('HTTP 业务错误不重试创建请求', async () => {
  const createGeneration = vi.fn(async () => {
    throw new HttpError(400, 'MODEL_UNAVAILABLE', '模型不可用');
  });
  const result = renderHook(() =>
    useConversationSession({ conversationClient: client({ createGeneration }), conversationId })
  );

  await act(async () => {
    await result.result.current.submit({ text: 'Do not retry', modelId });
  });

  expect(createGeneration).toHaveBeenCalledOnce();
  expect(result.result.current.error).toMatchObject({ code: 'MODEL_UNAVAILABLE' });
});

it('非终态流结束时释放提交状态并报告断开', async () => {
  const result = renderHook(() =>
    useConversationSession({
      conversationClient: client({ streamGeneration: vi.fn(async function* () {}) }),
      conversationId
    })
  );

  await act(async () => {
    await result.result.current.submit({ text: 'Disconnected', modelId });
  });

  expect(result.result.current.submitting).toBe(false);
  expect(result.result.current.error).toMatchObject({ code: 'AW-CONVERSATION-CLIENT-0002' });
});

it('流式事件经 generationReducer 更新正文并在完成后进入 SUCCEEDED', async () => {
  const streamGeneration = vi.fn(async function* () {
    yield event('generation.started', 1, { status: 'STREAMING' });
    yield event('content.delta', 2, { delta: '第一段' });
    yield event('content.delta', 3, { delta: '第二段' });
    yield event('generation.completed', 4, { status: 'SUCCEEDED' });
  });
  const result = renderHook(() =>
    useConversationSession({ conversationClient: client({ streamGeneration }), conversationId })
  );

  await act(async () => {
    await result.result.current.submit({ text: 'Stream', modelId });
  });

  await waitFor(() => expect(result.result.current.activeGeneration?.status).toBe('SUCCEEDED'));
  expect(result.result.current.activeGeneration?.content).toBe('第一段第二段');
  expect(result.result.current.submitting).toBe(false);
});

it('停止调用保留服务端部分文本并进入 STOPPED', async () => {
  let releaseStream!: () => void;
  const streamReady = new Promise<void>((resolve) => {
    releaseStream = resolve;
  });
  const stopGeneration = vi.fn(async () =>
    snapshot({
      status: 'STOPPED',
      content: { schemaVersion: 1, blocks: [{ type: 'text', text: '服务端部分文本' }] }
    })
  );
  const streamGeneration = vi.fn(async function* (_url, _lastEventId, signal) {
    yield event('generation.started', 1, { status: 'STREAMING' });
    yield event('content.delta', 2, { delta: '客户端部分' });
    await streamReady;
    if (signal?.aborted) {
      return;
    }
  });
  const result = renderHook(() =>
    useConversationSession({ conversationClient: client({ streamGeneration, stopGeneration }), conversationId })
  );

  await act(async () => {
    void result.result.current.submit({ text: 'Stop', modelId });
  });
  await waitFor(() => expect(result.result.current.activeGeneration?.status).toBe('STREAMING'));

  await act(async () => {
    await result.result.current.stop();
  });

  expect(stopGeneration).toHaveBeenCalledWith(generationId, expect.anything());
  expect(result.result.current.activeGeneration).toMatchObject({
    status: 'STOPPED',
    content: '服务端部分文本'
  });
  releaseStream();
});

it('卸载只 abort 浏览器事件订阅，不调用服务端 stop', async () => {
  let aborted = false;
  const streamGeneration = vi.fn(async function* (_url, _lastEventId, signal) {
    yield event('generation.started', 1, { status: 'STREAMING' });
    await new Promise<void>((resolve) => {
      signal?.addEventListener('abort', () => {
        aborted = true;
        resolve();
      });
    });
  });
  const stopGeneration = vi.fn();
  const result = renderHook(() =>
    useConversationSession({ conversationClient: client({ streamGeneration, stopGeneration }), conversationId })
  );

  await act(async () => {
    void result.result.current.submit({ text: 'Unmount', modelId });
  });
  await waitFor(() => expect(result.result.current.activeGeneration?.status).toBe('STREAMING'));

  result.unmount();

  await waitFor(() => expect(aborted).toBe(true));
  expect(stopGeneration).not.toHaveBeenCalled();
});
