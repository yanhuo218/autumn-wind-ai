import { describe, expect, it, vi } from 'vitest';

import { HttpError } from '../../../lib/http-error';
import { createConversationClient } from './conversation-client';

const conversationId = '00000000-0000-4000-8000-000000000001';
const generationId = '00000000-0000-4000-8000-000000000002';
const userMessageId = '00000000-0000-4000-8000-000000000003';
const requestId = '00000000-0000-4000-8000-000000000004';
const modelId = '00000000-0000-4000-8000-000000000005';
const correlationId = '00000000-0000-4000-8000-000000000006';

const conversation = {
  conversationId,
  title: 'Mock title',
  createdAt: '2026-07-19T12:00:00Z',
  archived: false
};

const generation = {
  generationId,
  conversationId,
  status: 'STREAMING',
  content: {
    schemaVersion: 1,
    blocks: [{ type: 'text', text: 'Mock content' }]
  },
  createdAt: '2026-07-19T12:00:00Z',
  updatedAt: '2026-07-19T12:00:01Z'
};

const accepted = {
  userMessageId,
  generationId,
  statusUrl: `/api/v1/generations/${generationId}`,
  eventsUrl: `/api/v1/generations/${generationId}/events`
};

function jsonResponse(body: unknown, status = 200): Response {
  return typedJsonResponse(body, status, 'application/json');
}

function typedJsonResponse(body: unknown, status: number, contentType: string): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': contentType }
  });
}

function capturedRequest(input: RequestInfo | URL, init?: RequestInit): Request {
  if (input instanceof Request) {
    return input;
  }
  const value = input instanceof URL ? input.href : input;
  return new Request(new URL(value, 'https://mock.invalid'), init);
}

function streamResponse(text: string): Response {
  const bytes = new TextEncoder().encode(text);
  return new Response(
    new ReadableStream({
      start(controller) {
        controller.enqueue(bytes);
        controller.close();
      }
    }),
    { status: 200, headers: { 'Content-Type': 'text/event-stream' } }
  );
}

describe('ConversationClient', () => {
  it('列出会话时包含凭据并校验 JSON 响应', async () => {
    let request: Request | undefined;
    const fetchImpl: typeof fetch = vi.fn(async (input, init) => {
      request = capturedRequest(input, init);
      return jsonResponse({ items: [conversation] });
    });

    const result = await createConversationClient(fetchImpl).listConversations();

    expect(result).toEqual({ items: [conversation] });
    expect(request?.url).toBe('https://mock.invalid/api/v1/conversations');
    expect(request?.credentials).toBe('include');
    expect(request?.headers.get('Accept')).toBe('application/json');
  });

  it('创建和读取会话时使用对应方法、编码路径并发送 JSON', async () => {
    const requests: Request[] = [];
    const responses = [
      jsonResponse(conversation, 201),
      jsonResponse({ ...conversation, generations: [], messages: [] })
    ];
    const fetchImpl: typeof fetch = vi.fn(async (input, init) => {
      requests.push(capturedRequest(input, init));
      const response = responses.shift();
      if (!response) {
        throw new Error('Mock response missing');
      }
      return response;
    });
    const client = createConversationClient(fetchImpl);

    await expect(client.createConversation('Mock title')).resolves.toEqual(conversation);
    await expect(client.getConversation('id/with slash')).resolves.toMatchObject({ generations: [] });

    expect(requests[0].method).toBe('POST');
    expect(requests[0].headers.get('Content-Type')).toBe('application/json');
    await expect(requests[0].json()).resolves.toEqual({ title: 'Mock title' });
    expect(requests[1].url).toBe('https://mock.invalid/api/v1/conversations/id%2Fwith%20slash');
  });

  it('归档会话接受 204 空响应', async () => {
    let request: Request | undefined;
    const fetchImpl: typeof fetch = vi.fn(async (input, init) => {
      request = capturedRequest(input, init);
      return new Response(null, { status: 204 });
    });

    await expect(createConversationClient(fetchImpl).archiveConversation(conversationId)).resolves.toBeUndefined();

    expect(request?.method).toBe('DELETE');
  });

  it('创建生成时校验受理响应和服务返回链接', async () => {
    const fetchImpl: typeof fetch = vi.fn(async () => jsonResponse(accepted, 202));
    const client = createConversationClient(fetchImpl);

    await expect(
      client.createGeneration(conversationId, {
        clientRequestId: requestId,
        modelId,
        content: { schemaVersion: 1, blocks: [{ type: 'text', text: 'Mock prompt' }] }
      })
    ).resolves.toEqual(accepted);
  });

  it.each([
    'https://external.invalid/api/v1/events',
    'http://external.invalid/api/v1/events',
    '//external.invalid/api/v1/events',
    '/other/v1/events'
  ])('拒绝不受信任的服务返回链接 %s', async (eventsUrl) => {
    const fetchImpl: typeof fetch = vi.fn(async () => jsonResponse({ ...accepted, eventsUrl }, 202));

    await expect(
      createConversationClient(fetchImpl).createGeneration(conversationId, {
        clientRequestId: requestId,
        modelId,
        content: { schemaVersion: 1, blocks: [{ type: 'text', text: 'Mock prompt' }] }
      })
    ).rejects.toMatchObject({ code: 'PROTOCOL_ERROR' });
  });

  it('拒绝不受信任的 statusUrl', async () => {
    const fetchImpl: typeof fetch = vi.fn(async () =>
      jsonResponse({ ...accepted, statusUrl: 'https://external.invalid/api/v1/status' }, 202)
    );

    await expect(
      createConversationClient(fetchImpl).regenerate(generationId, { clientRequestId: requestId })
    ).rejects.toMatchObject({ code: 'PROTOCOL_ERROR' });
  });

  it('读取、停止和重新生成均使用对应契约守卫', async () => {
    const responses = [jsonResponse(generation), jsonResponse({ ...generation, status: 'STOPPED' }), jsonResponse(accepted, 202)];
    const fetchImpl: typeof fetch = vi.fn(async () => {
      const response = responses.shift();
      if (!response) {
        throw new Error('Mock response missing');
      }
      return response;
    });
    const client = createConversationClient(fetchImpl);

    await expect(client.getGeneration(generationId)).resolves.toEqual(generation);
    await expect(client.stopGeneration(generationId)).resolves.toMatchObject({ status: 'STOPPED' });
    await expect(client.regenerate(generationId, { clientRequestId: requestId })).resolves.toEqual(accepted);
  });

  it('将合法公共错误转换为受限 HttpError', async () => {
    const fetchImpl: typeof fetch = vi.fn(async () =>
      jsonResponse(
        {
          code: 'AW-CONVERSATION-NOT_FOUND-0001',
          message: 'Mock not found',
          correlationId
        },
        404
      )
    );

    const error = await createConversationClient(fetchImpl).getConversation(conversationId).catch((reason: unknown) => reason);

    expect(error).toBeInstanceOf(HttpError);
    expect(error).toMatchObject({
      status: 404,
      code: 'AW-CONVERSATION-NOT_FOUND-0001',
      message: 'Mock not found',
      correlationId
    });
    expect(error).not.toHaveProperty('response');
    expect(error).not.toHaveProperty('body');
  });

  it('成功或错误 JSON 未通过契约时抛出不含原文的协议错误', async () => {
    const invalidSuccess = createConversationClient(vi.fn(async () => jsonResponse({ items: 'invalid' })));
    const invalidError = createConversationClient(
      vi.fn(async () => jsonResponse({ message: 'PRIVATE_MOCK_DETAIL' }, 500))
    );

    await expect(invalidSuccess.listConversations()).rejects.toMatchObject({ code: 'PROTOCOL_ERROR' });
    await expect(invalidError.listConversations()).rejects.toMatchObject({ code: 'PROTOCOL_ERROR' });
    await expect(invalidError.listConversations()).rejects.not.toThrow('PRIVATE_MOCK_DETAIL');
  });

  it('拒绝成功 JSON 的非 JSON media type', async () => {
    const client = createConversationClient(
      vi.fn(async () => typedJsonResponse({ items: [conversation] }, 200, 'text/plain'))
    );

    await expect(client.listConversations()).rejects.toMatchObject({ code: 'PROTOCOL_ERROR' });
  });

  it('接受带参数的 application/*+json 公共错误并拒绝错误文本 media type', async () => {
    const publicError = {
      code: 'AW-CONVERSATION-NOT_FOUND-0001',
      message: 'Mock not found',
      correlationId
    };
    const compatible = createConversationClient(
      vi.fn(async () => typedJsonResponse(publicError, 404, 'application/problem+json; charset=utf-8'))
    );
    const incompatible = createConversationClient(
      vi.fn(async () => typedJsonResponse(publicError, 404, 'text/plain'))
    );

    await expect(compatible.getConversation(conversationId)).rejects.toMatchObject({
      status: 404,
      code: publicError.code
    });
    await expect(incompatible.getConversation(conversationId)).rejects.toMatchObject({
      code: 'PROTOCOL_ERROR'
    });
  });

  it('以 SSE 请求头和 Last-Event-ID 增量读取事件', async () => {
    const payload = {
      eventId: 'event-0000000001',
      eventType: 'content.delta',
      generationId,
      sequence: 1,
      occurredAt: '2026-07-19T12:00:00Z',
      payloadVersion: 1,
      payload: { delta: 'Mock content' }
    };
    let request: Request | undefined;
    const fetchImpl: typeof fetch = vi.fn(async (input, init) => {
      request = capturedRequest(input, init);
      return streamResponse(`data: ${JSON.stringify(payload)}\n\n`);
    });
    const events = [];

    for await (const event of createConversationClient(fetchImpl).streamGeneration(accepted.eventsUrl, 'event-0000000000')) {
      events.push(event);
    }

    expect(events).toEqual([payload]);
    expect(request?.credentials).toBe('include');
    expect(request?.headers.get('Accept')).toBe('text/event-stream');
    expect(request?.headers.get('Last-Event-ID')).toBe('event-0000000000');
  });

  it('拒绝错误的 SSE media type 和 null body', async () => {
    const wrongMedia = createConversationClient(
      vi.fn(async () =>
        new Response(new TextEncoder().encode('data: {}\n\n'), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
      )
    );
    const nullBody = createConversationClient(
      vi.fn(async () => new Response(null, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))
    );

    const consumeWrongMedia = async () => {
      for await (const _event of wrongMedia.streamGeneration(accepted.eventsUrl)) {
        void _event;
      }
    };
    const consumeNullBody = async () => {
      for await (const _event of nullBody.streamGeneration(accepted.eventsUrl)) {
        void _event;
      }
    };

    await expect(consumeWrongMedia()).rejects.toMatchObject({ code: 'PROTOCOL_ERROR' });
    await expect(consumeNullBody()).rejects.toMatchObject({ code: 'PROTOCOL_ERROR' });
  });

  it.each([
    'https://external.invalid/api/v1/events',
    '//external.invalid/api/v1/events',
    '/api/v1\\external.invalid/events',
    '/api/v1/events#fragment',
    ' /api/v1/events',
    '/api/v1/events ',
    '/api/v1/../admin',
    '/api/v1/%2e%2e/admin',
    '/api/v1/%2E%2E/admin',
    '/api/v1/%2e%2E/admin'
  ])('在发出请求前拒绝路径攻击 %s', async (eventsUrl) => {
    const fetchImpl: typeof fetch = vi.fn();
    const consume = async () => {
      for await (const _event of createConversationClient(fetchImpl).streamGeneration(eventsUrl)) {
        void _event;
      }
    };

    await expect(consume()).rejects.toMatchObject({ code: 'PROTOCOL_ERROR' });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('允许同源 API 路径携带 query', async () => {
    const payload = {
      eventId: 'event-0000000002',
      eventType: 'stream.heartbeat',
      generationId,
      sequence: 2,
      occurredAt: '2026-07-19T12:00:00Z',
      payloadVersion: 1,
      payload: {}
    };
    const fetchImpl: typeof fetch = vi.fn(async () =>
      streamResponse(`data: ${JSON.stringify(payload)}\n\n`)
    );
    const events = [];

    for await (const event of createConversationClient(fetchImpl).streamGeneration(
      `${accepted.eventsUrl}?cursor=Mock`
    )) {
      events.push(event);
    }

    expect(events).toEqual([payload]);
    expect(fetchImpl).toHaveBeenCalledOnce();
  });
});
