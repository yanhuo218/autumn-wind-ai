import { describe, expect, it } from 'vitest';

import { isConversationStreamEvent } from './validate-conversation-event';

const validContentDelta = {
  eventId: 'event-0000000001',
  eventType: 'content.delta',
  generationId: '00000000-0000-4000-8000-000000000001',
  sequence: 1,
  occurredAt: '2026-07-19T12:00:00Z',
  payloadVersion: 1,
  payload: {
    delta: '你好'
  }
};

describe('isConversationStreamEvent', () => {
  it('接受合法的 content.delta 事件', () => {
    expect(isConversationStreamEvent(validContentDelta)).toBe(true);
  });

  it('拒绝缺少 sequence 的事件', () => {
    const { sequence: _sequence, ...eventWithoutSequence } = validContentDelta;

    expect(isConversationStreamEvent(eventWithoutSequence)).toBe(false);
  });

  it('拒绝未知 eventType', () => {
    expect(
      isConversationStreamEvent({
        ...validContentDelta,
        eventType: 'content.unknown'
      })
    ).toBe(false);
  });
});
