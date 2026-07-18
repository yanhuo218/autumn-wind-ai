import type {
  ConversationStreamEventV1,
  GenerationAcceptedView,
  GenerationView
} from '@autumn-wind/api-contracts';
import { describe, expect, it, vi } from 'vitest';

import type { ConversationClient } from '../api/conversation-client';
import { HttpError } from '../../../lib/http-error';
import { consumeGenerationStream } from './generation-stream-controller';

const generationId = '00000000-0000-4000-8000-000000000001';
const conversationId = '00000000-0000-4000-8000-000000000002';
const accepted = {
  userMessageId: '00000000-0000-4000-8000-000000000003',
  generationId,
  statusUrl: `/api/v1/generations/${generationId}`,
  eventsUrl: `/api/v1/generations/${generationId}/events`
} satisfies GenerationAcceptedView;

function event(
  sequence: number,
  eventType: ConversationStreamEventV1['eventType'],
  payload: ConversationStreamEventV1['payload']
): ConversationStreamEventV1 {
  return {
    eventId: `event-${sequence.toString().padStart(10, '0')}`,
    generationId,
    sequence,
    occurredAt: '2026-07-19T12:00:00Z',
    payloadVersion: 1,
    eventType,
    payload
  } as ConversationStreamEventV1;
}

async function* events(...values: ConversationStreamEventV1[]): AsyncGenerator<ConversationStreamEventV1> {
  yield* values;
}

function snapshot(status: GenerationView['status'] = 'STREAMING'): GenerationView {
  return {
    generationId,
    conversationId,
    status,
    content: { schemaVersion: 1, blocks: [{ type: 'text', text: '快照正文' }] },
    createdAt: '2026-07-19T12:00:00Z',
    updatedAt: '2026-07-19T12:00:01Z'
  };
}

describe('consumeGenerationStream', () => {
  it('非终态断流以最后 eventId 按固定退避重连五次', async () => {
    const first = event(1, 'content.delta', { delta: '第一段' });
    const streamGeneration = vi.fn((_: string, lastEventId?: string) =>
      lastEventId === undefined ? events(first) : events()
    );
    const sleep = vi.fn(async () => undefined);
    const connectionStates: string[] = [];

    await consumeGenerationStream({
      accepted,
      client: { streamGeneration, getGenerationSnapshot: vi.fn() } as unknown as ConversationClient,
      onEvent: vi.fn(),
      onSnapshot: vi.fn(),
      onConnectionState: (state) => connectionStates.push(state),
      signal: new AbortController().signal,
      sleep
    });

    expect(streamGeneration.mock.calls.map(([, lastEventId]) => lastEventId)).toEqual([
      undefined,
      first.eventId,
      first.eventId,
      first.eventId,
      first.eventId,
      first.eventId
    ]);
    expect(sleep).toHaveBeenNthCalledWith(1, 250);
    expect(sleep).toHaveBeenNthCalledWith(2, 500);
    expect(sleep).toHaveBeenNthCalledWith(3, 1000);
    expect(sleep).toHaveBeenNthCalledWith(4, 2000);
    expect(sleep).toHaveBeenNthCalledWith(5, 4000);
    expect(connectionStates).toEqual([
      'CONNECTED',
      'RECONNECTING',
      'CONNECTED',
      'RECONNECTING',
      'CONNECTED',
      'RECONNECTING',
      'CONNECTED',
      'RECONNECTING',
      'CONNECTED',
      'RECONNECTING',
      'CONNECTED',
      'DISCONNECTED'
    ]);
  });

  it('收到终态事件后结束，不再重新订阅', async () => {
    const completed = event(1, 'generation.completed', { status: 'SUCCEEDED' });
    const streamGeneration = vi.fn(() => events(completed));
    const sleep = vi.fn(async () => undefined);

    await consumeGenerationStream({
      accepted,
      client: { streamGeneration, getGenerationSnapshot: vi.fn() } as unknown as ConversationClient,
      onEvent: vi.fn(),
      onSnapshot: vi.fn(),
      onConnectionState: vi.fn(),
      signal: new AbortController().signal,
      sleep
    });

    expect(streamGeneration).toHaveBeenCalledOnce();
    expect(sleep).not.toHaveBeenCalled();
  });

  it('明确的 HTTP 业务错误不进入断线重连', async () => {
    const streamGeneration = vi.fn(async function* () {
      throw new HttpError(404, 'GENERATION_NOT_FOUND', '生成不存在');
    });
    const sleep = vi.fn(async () => undefined);

    await consumeGenerationStream({
      accepted,
      client: { streamGeneration, getGenerationSnapshot: vi.fn() } as unknown as ConversationClient,
      onEvent: vi.fn(),
      onSnapshot: vi.fn(),
      onConnectionState: vi.fn(),
      signal: new AbortController().signal,
      sleep
    });

    expect(streamGeneration).toHaveBeenCalledOnce();
    expect(sleep).not.toHaveBeenCalled();
  });

  it('部分事件后收到 HTTP 业务错误也不覆盖不重连决定', async () => {
    const delta = event(1, 'content.delta', { delta: '已收到' });
    const streamGeneration = vi.fn(async function* () {
      yield delta;
      throw new HttpError(409, 'GENERATION_CLOSED', '生成已关闭');
    });
    const sleep = vi.fn(async () => undefined);

    await consumeGenerationStream({
      accepted,
      client: {
        streamGeneration,
        getGenerationSnapshot: vi.fn()
      } as unknown as ConversationClient,
      onEvent: vi.fn(),
      onSnapshot: vi.fn(),
      onConnectionState: vi.fn(),
      signal: new AbortController().signal,
      sleep
    });

    expect(streamGeneration).toHaveBeenCalledOnce();
    expect(sleep).not.toHaveBeenCalled();
  });

  it('replay.reset 拉取快照并从 reset eventId 继续订阅', async () => {
    const reset = event(1, 'replay.reset', { snapshotUrl: accepted.statusUrl });
    const completed = event(2, 'generation.completed', { status: 'SUCCEEDED' });
    const streamGeneration = vi.fn((_: string, lastEventId?: string) =>
      lastEventId === undefined ? events(reset, completed) : events(completed)
    );
    const onEvent = vi.fn();
    const onSnapshot = vi.fn();
    const client = {
      streamGeneration,
      getGeneration: vi.fn(),
      getGenerationSnapshot: vi.fn(async () => snapshot())
    } as unknown as ConversationClient;

    await consumeGenerationStream({
      accepted,
      client,
      onEvent,
      onSnapshot,
      onConnectionState: vi.fn(),
      signal: new AbortController().signal,
      sleep: vi.fn(async () => undefined)
    });

    expect(client.getGenerationSnapshot).toHaveBeenCalledWith(accepted.statusUrl, expect.any(AbortSignal));
    expect(onSnapshot).toHaveBeenCalledWith(snapshot());
    expect(streamGeneration.mock.calls.map(([, lastEventId]) => lastEventId)).toEqual([
      undefined,
      reset.eventId
    ]);
    expect(onEvent).toHaveBeenCalledTimes(2);
    expect(onEvent).toHaveBeenNthCalledWith(1, reset);
    expect(onEvent).toHaveBeenNthCalledWith(2, completed);
  });

  it('快照获取失败时不以旧内容完成，并保留 reset 光标重试', async () => {
    const reset = event(1, 'replay.reset', { snapshotUrl: accepted.statusUrl });
    const streamGeneration = vi.fn((_eventsUrl: string, _lastEventId?: string) => events(reset));
    const getGeneration = vi.fn(async () => {
      throw new Error('快照不可用');
    });
    const onSnapshot = vi.fn();

    await consumeGenerationStream({
      accepted,
      client: { streamGeneration, getGenerationSnapshot: getGeneration } as unknown as ConversationClient,
      onEvent: vi.fn(),
      onSnapshot,
      onConnectionState: vi.fn(),
      signal: new AbortController().signal,
      sleep: vi.fn(async () => undefined)
    });

    expect(onSnapshot).not.toHaveBeenCalled();
    expect(streamGeneration.mock.calls.map(([, lastEventId]) => lastEventId)).toEqual([
      undefined,
      reset.eventId,
      reset.eventId,
      reset.eventId,
      reset.eventId,
      reset.eventId
    ]);
  });
});
