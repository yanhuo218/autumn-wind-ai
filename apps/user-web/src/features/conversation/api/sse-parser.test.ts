import { describe, expect, it } from 'vitest';

import { parseConversationEventStream } from './sse-parser';

const generationId = '00000000-0000-4000-8000-000000000001';

function event(sequence: number, delta = 'Mock content') {
  return {
    eventId: `event-${sequence.toString().padStart(10, '0')}`,
    eventType: 'content.delta',
    generationId,
    sequence,
    occurredAt: '2026-07-19T12:00:00Z',
    payloadVersion: 1,
    payload: { delta }
  };
}

function streamFromChunks(chunks: Uint8Array[]): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(chunk);
      }
      controller.close();
    }
  });
}

async function collect(stream: ReadableStream<Uint8Array>) {
  const events = [];
  for await (const parsedEvent of parseConversationEventStream(stream)) {
    events.push(parsedEvent);
  }
  return events;
}

describe('parseConversationEventStream', () => {
  it('跨字节块和 UTF-8 边界增量解析同一个 frame', async () => {
    const encoder = new TextEncoder();
    const bytes = encoder.encode(`data: ${JSON.stringify(event(1, 'Mock 内容'))}\n\n`);
    const splitAt = bytes.indexOf(0xe5) + 1;

    const result = await collect(streamFromChunks([bytes.slice(0, splitAt), bytes.slice(splitAt)]));

    expect(result).toEqual([event(1, 'Mock 内容')]);
  });

  it('解析同一字节块内的多个 frame', async () => {
    const text = `data: ${JSON.stringify(event(1))}\n\ndata: ${JSON.stringify(event(2))}\n\n`;

    const result = await collect(streamFromChunks([new TextEncoder().encode(text)]));

    expect(result).toEqual([event(1), event(2)]);
  });

  it('支持 CRLF、注释 heartbeat、id、event 和多行 data', async () => {
    const json = JSON.stringify(event(1));
    const midpoint = json.indexOf('"payload"');
    const text = [
      ': heartbeat',
      '',
      'id: event-0000000001',
      'event: content.delta',
      `data: ${json.slice(0, midpoint)}`,
      `data: ${json.slice(midpoint)}`,
      '',
      ''
    ].join('\r\n');

    const result = await collect(streamFromChunks([new TextEncoder().encode(text)]));

    expect(result).toEqual([event(1)]);
  });

  it('拒绝非法 JSON 且不暴露 frame 原文', async () => {
    const stream = streamFromChunks([new TextEncoder().encode('data: {invalid}\n\n')]);

    await expect(collect(stream)).rejects.toThrow('事件流协议无效');
    await expect(collect(streamFromChunks([new TextEncoder().encode('data: {invalid}\n\n')]))).rejects.not.toThrow(
      '{invalid}'
    );
  });

  it('拒绝未通过契约校验的事件', async () => {
    const invalidEvent = { ...event(1), sequence: '1' };
    const stream = streamFromChunks([
      new TextEncoder().encode(`data: ${JSON.stringify(invalidEvent)}\n\n`)
    ]);

    await expect(collect(stream)).rejects.toThrow('事件流协议无效');
  });

  it('拒绝 JSON 字符串中的非法 UTF-8，即使替换字符仍通过契约', async () => {
    const encoder = new TextEncoder();
    const prefix = encoder.encode(
      'data: {"eventId":"event-0000000001","eventType":"content.delta","generationId":"00000000-0000-4000-8000-000000000001","sequence":1,"occurredAt":"2026-07-19T12:00:00Z","payloadVersion":1,"payload":{"delta":"Mock '
    );
    const suffix = encoder.encode('"}}\n\n');

    await expect(collect(streamFromChunks([prefix, Uint8Array.from([0xff]), suffix]))).rejects.toThrow(
      '事件流协议无效'
    );
  });

  it('在流结束时解析末尾没有空行的残留 frame', async () => {
    const stream = streamFromChunks([
      new TextEncoder().encode(`data: ${JSON.stringify(event(1))}`)
    ]);

    await expect(collect(stream)).resolves.toEqual([event(1)]);
  });
});
