import type {
  ConversationStreamEventV1,
  GenerationView
} from '@autumn-wind/api-contracts';
import { describe, expect, it } from 'vitest';

import {
  createGenerationUiState,
  generationReducer,
  replaceGenerationSnapshot
} from './generation-reducer';

const generationId = '00000000-0000-4000-8000-000000000001';
const conversationId = '00000000-0000-4000-8000-000000000002';
const correlationId = '00000000-0000-4000-8000-000000000003';

const snapshot = {
  generationId,
  conversationId,
  status: 'PENDING',
  content: {
    schemaVersion: 1,
    blocks: [{ type: 'text', text: 'Mock initial' }]
  },
  createdAt: '2026-07-19T12:00:00Z',
  updatedAt: '2026-07-19T12:00:01Z'
} satisfies GenerationView;

function envelope(sequence: number): {
  eventId: string;
  generationId: string;
  sequence: number;
  occurredAt: string;
  payloadVersion: 1;
} {
  return {
    eventId: `event-${sequence.toString().padStart(10, '0')}`,
    generationId,
    sequence,
    occurredAt: '2026-07-19T12:00:00Z',
    payloadVersion: 1
  };
}

describe('generationReducer', () => {
  it('从快照创建 PENDING 状态并处理 generation.started', () => {
    const started = {
      ...envelope(1),
      eventType: 'generation.started',
      payload: { status: 'STREAMING' }
    } satisfies ConversationStreamEventV1;

    const state = generationReducer(createGenerationUiState(snapshot), started);

    expect(state).toMatchObject({
      generationId,
      status: 'STREAMING',
      content: 'Mock initial',
      reasoning: '',
      lastEventId: started.eventId,
      lastSequence: 1
    });
  });

  it('追加 reasoning/content delta 并用 checkpoint 替换正文', () => {
    const reasoning = {
      ...envelope(1),
      eventType: 'reasoning.delta',
      payload: { delta: 'Mock reason' }
    } satisfies ConversationStreamEventV1;
    const content = {
      ...envelope(2),
      eventType: 'content.delta',
      payload: { delta: ' plus delta' }
    } satisfies ConversationStreamEventV1;
    const checkpoint = {
      ...envelope(3),
      eventType: 'content.checkpoint',
      payload: {
        content: {
          schemaVersion: 1,
          blocks: [
            { type: 'text', text: 'Mock checkpoint' },
            { type: 'image_ref', resourceId: '00000000-0000-4000-8000-000000000004' }
          ]
        },
        throughSequence: 2
      }
    } satisfies ConversationStreamEventV1;

    let state = createGenerationUiState(snapshot);
    state = generationReducer(state, reasoning);
    state = generationReducer(state, content);
    state = generationReducer(state, checkpoint);

    expect(state.reasoning).toBe('Mock reason');
    expect(state.content).toBe('Mock checkpoint');
  });

  it('usage.updated 保留 token 计数中的 null', () => {
    const usage = {
      ...envelope(1),
      eventType: 'usage.updated',
      payload: { promptTokens: 12, completionTokens: null, totalTokens: null }
    } satisfies ConversationStreamEventV1;

    const state = generationReducer(createGenerationUiState(snapshot), usage);

    expect(state.usage).toEqual({ promptTokens: 12, completionTokens: null, totalTokens: null });
  });

  it.each([
    {
      event: {
        ...envelope(1),
        eventType: 'generation.completed',
        payload: { status: 'SUCCEEDED' }
      } satisfies ConversationStreamEventV1,
      status: 'SUCCEEDED'
    },
    {
      event: {
        ...envelope(1),
        eventType: 'generation.failed',
        payload: {
          status: 'FAILED',
          code: 'AW-CONVERSATION-MOCK-0001',
          message: 'Mock failure',
          correlationId
        }
      } satisfies ConversationStreamEventV1,
      status: 'FAILED'
    },
    {
      event: {
        ...envelope(1),
        eventType: 'generation.stopped',
        payload: { status: 'STOPPED' }
      } satisfies ConversationStreamEventV1,
      status: 'STOPPED'
    },
    {
      event: {
        ...envelope(1),
        eventType: 'generation.interrupted',
        payload: {
          status: 'INTERRUPTED',
          code: 'AW-CONVERSATION-MOCK-0002',
          message: 'Mock interrupted',
          correlationId
        }
      } satisfies ConversationStreamEventV1,
      status: 'INTERRUPTED'
    }
  ])('终态 $status 为吸收态', ({ event, status }) => {
    const state = generationReducer(createGenerationUiState(snapshot), event);
    expect(state.status).toBe(status);
    if (status === 'FAILED' || status === 'INTERRUPTED') {
      expect(state.error).toBeDefined();
    }

    const delta = {
      ...envelope(2),
      eventType: 'content.delta',
      payload: { delta: ' after terminal' }
    } satisfies ConversationStreamEventV1;
    const competingTerminal = { ...event, eventId: 'event-0000000003', sequence: 3 };

    expect(generationReducer(state, delta)).toBe(state);
    expect(generationReducer(state, competingTerminal)).toBe(state);
  });

  it('按 eventId 幂等、忽略 sequence 回退并接受 heartbeat 元数据', () => {
    const delta = {
      ...envelope(2),
      eventType: 'content.delta',
      payload: { delta: ' delta' }
    } satisfies ConversationStreamEventV1;
    const stale = {
      ...envelope(1),
      eventType: 'content.delta',
      payload: { delta: ' stale' }
    } satisfies ConversationStreamEventV1;
    const heartbeat = {
      ...envelope(3),
      eventType: 'stream.heartbeat',
      payload: {}
    } satisfies ConversationStreamEventV1;

    let state = generationReducer(createGenerationUiState(snapshot), delta);
    state = generationReducer(state, delta);
    state = generationReducer(state, stale);
    state = generationReducer(state, heartbeat);

    expect(state.content).toBe('Mock initial delta');
    expect(state.lastSequence).toBe(3);
    expect(state.lastEventId).toBe(heartbeat.eventId);
    expect(state.seenEventIds).toEqual(new Set([delta.eventId, heartbeat.eventId]));
  });

  it('忽略相同 sequence 的不同 eventId', () => {
    const first = {
      ...envelope(1),
      eventType: 'content.delta',
      payload: { delta: ' first' }
    } satisfies ConversationStreamEventV1;
    const sameSequence = {
      ...first,
      eventId: 'event-0000000002',
      payload: { delta: ' duplicate sequence' }
    } satisfies ConversationStreamEventV1;
    const state = generationReducer(createGenerationUiState(snapshot), first);

    expect(generationReducer(state, sameSequence)).toBe(state);
  });

  it('replay.reset 只进入 SYNCING，快照替换后退出同步', () => {
    const reset = {
      ...envelope(1),
      eventType: 'replay.reset',
      payload: { snapshotUrl: `/api/v1/generations/${generationId}` }
    } satisfies ConversationStreamEventV1;
    const refreshed = {
      ...snapshot,
      status: 'STREAMING',
      content: { schemaVersion: 1, blocks: [{ type: 'text', text: 'Mock snapshot' }] },
      updatedAt: '2026-07-19T12:00:02Z',
      error: {
        code: 'AW-CONVERSATION-MOCK-0003',
        message: 'Mock snapshot error',
        correlationId
      }
    } satisfies GenerationView;
    const mismatched = { ...refreshed, generationId: '00000000-0000-4000-8000-000000000005' } satisfies GenerationView;

    const syncing = generationReducer(createGenerationUiState(snapshot), reset);
    const replaced = replaceGenerationSnapshot(syncing, refreshed);
    const rejected = replaceGenerationSnapshot(syncing, mismatched);

    expect(syncing).toMatchObject({ status: 'SYNCING', content: 'Mock initial' });
    expect(replaced).toMatchObject({
      generationId,
      status: 'STREAMING',
      content: 'Mock snapshot',
      error: { code: 'AW-CONVERSATION-MOCK-0003' },
      lastEventId: reset.eventId,
      lastSequence: 1
    });
    expect(replaced.seenEventIds).toEqual(new Set([reset.eventId]));
    expect(rejected).toBe(syncing);
  });
});
