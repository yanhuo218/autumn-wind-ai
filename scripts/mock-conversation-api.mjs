import { randomUUID } from 'node:crypto';
import { createServer } from 'node:http';

const host = '127.0.0.1';
const port = parsePort(process.env.PORT);
const conversations = new Map();
const generations = new Map();
const idempotentGenerations = new Map();
const supportedScenarios = new Set(['success', 'slow', 'failed', 'interrupted', 'replay-reset']);
const errorCodes = Object.freeze({
  notFound: 'AW-CONVERSATION-NOT_FOUND-0001',
  conflict: 'AW-CONVERSATION-CONFLICT-0001',
  validation: 'AW-CONVERSATION-VALIDATION-0001',
  upstreamTimeout: 'AW-CONVERSATION-DEPENDENCY-0003',
  upstreamInterrupted: 'AW-CONVERSATION-DEPENDENCY-0004',
  rateLimit: 'AW-CONVERSATION-RATE_LIMIT-0001',
  internal: 'AW-CONVERSATION-INTERNAL-0001'
});

const server = createServer(async (request, response) => {
  const correlationId = randomUUID();
  response.setHeader('X-Correlation-ID', correlationId);

  try {
    const url = new URL(request.url, `http://${host}`);
    const segments = url.pathname.split('/').filter(Boolean);

    if (segments[0] !== 'api' || segments[1] !== 'v1') {
      return sendError(response, 404, errorCodes.notFound, '资源不存在。', correlationId);
    }

    if (segments.length === 3 && segments[2] === 'conversations') {
      if (request.method === 'POST') {
        return await createConversation(request, response, correlationId);
      }
      if (request.method === 'GET') {
        return sendJson(response, 200, {
          items: [...conversations.values()].map(toConversationView)
        });
      }
    }

    if (segments.length === 4 && segments[2] === 'conversations') {
      const conversation = conversations.get(segments[3]);
      if (!conversation) {
        return sendError(response, 404, errorCodes.notFound, '会话不存在。', correlationId);
      }
      if (request.method === 'GET') {
        return sendJson(response, 200, {
          ...toConversationView(conversation),
          generations: conversation.generationIds.map((id) => toGenerationView(generations.get(id))),
          messages: toMessageViews(conversation)
        });
      }
      if (request.method === 'DELETE') {
        conversation.archived = true;
        response.writeHead(204);
        return response.end();
      }
    }

    if (segments.length === 5 && segments[2] === 'conversations' && segments[4] === 'generations' && request.method === 'POST') {
      const conversation = conversations.get(segments[3]);
      if (!conversation) {
        return sendError(response, 404, errorCodes.notFound, '会话不存在。', correlationId);
      }
      if (conversation.archived) {
        return sendError(response, 409, errorCodes.conflict, '已归档会话不能创建生成。', correlationId);
      }
      const scenario = url.searchParams.get('scenario') ?? 'success';
      return await createGeneration(request, response, conversation, scenario, correlationId);
    }

    if (segments[2] === 'generations' && segments[3]) {
      const generation = generations.get(segments[3]);
      if (!generation) {
        return sendError(response, 404, errorCodes.notFound, '生成不存在。', correlationId);
      }

      if (segments.length === 4 && request.method === 'GET') {
        return sendJson(response, 200, toGenerationView(generation));
      }
      if (segments.length === 5 && segments[4] === 'events' && request.method === 'GET') {
        return await streamEvents(request, response, generation);
      }
      if (segments.length === 5 && segments[4] === 'stop' && request.method === 'POST') {
        return stopGeneration(response, generation, correlationId);
      }
      if (segments.length === 5 && segments[4] === 'regenerate' && request.method === 'POST') {
        const conversation = conversations.get(generation.conversationId);
        const scenario = url.searchParams.get('scenario') ?? 'success';
        return await regenerate(request, response, conversation, generation, scenario, correlationId);
      }
    }

    return sendError(response, 404, errorCodes.notFound, '资源不存在。', correlationId);
  } catch (error) {
    if (error instanceof RequestError) {
      return sendError(response, error.status, error.code, error.message, correlationId);
    }
    return sendError(response, 500, errorCodes.internal, 'Mock 处理请求时发生内部错误。', correlationId);
  }
});

server.on('error', (error) => {
  console.error(`Mock Conversation API 启动失败：${error.message}`);
  process.exitCode = 1;
});

server.listen(port, host, () => {
  const address = server.address();
  console.log(`Mock Conversation API listening at http://${host}:${address.port}`);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    server.close(() => process.exit(0));
  });
}

async function createConversation(request, response, correlationId) {
  const body = await readJson(request);
  assertAllowedProperties(body, ['title']);
  if (body.title !== undefined && (typeof body.title !== 'string' || body.title.length < 1 || body.title.length > 200)) {
    throw new RequestError(400, errorCodes.validation, 'title 必须是 1 至 200 个字符。');
  }

  const now = new Date().toISOString();
  const conversation = {
    conversationId: randomUUID(),
    title: body.title ?? '新会话',
    createdAt: now,
    archived: false,
    generationIds: [],
    turns: []
  };
  conversations.set(conversation.conversationId, conversation);
  return sendJson(response, 201, toConversationView(conversation));
}

async function createGeneration(request, response, conversation, scenario, correlationId) {
  if (!supportedScenarios.has(scenario)) {
    return sendError(response, 400, errorCodes.validation, '不支持该 Mock 场景。', correlationId);
  }

  const body = await readJson(request);
  validateGenerationRequest(body);
  const idempotencyKey = `${conversation.conversationId}:${body.clientRequestId}`;
  const existingId = idempotentGenerations.get(idempotencyKey);
  if (existingId) {
    return sendJson(response, 202, toAcceptedView(generations.get(existingId)));
  }

  const generation = newGeneration(conversation.conversationId, body, scenario, correlationId);
  generations.set(generation.generationId, generation);
  idempotentGenerations.set(idempotencyKey, generation.generationId);
  conversation.generationIds.push(generation.generationId);
  conversation.turns.push({
    userMessageId: generation.userMessageId,
    content: generation.requestContent,
    createdAt: generation.createdAt,
    activeGenerationId: generation.generationId
  });
  return sendJson(response, 202, toAcceptedView(generation));
}

async function regenerate(request, response, conversation, source, scenario, correlationId) {
  if (!supportedScenarios.has(scenario)) {
    return sendError(response, 400, errorCodes.validation, '不支持该 Mock 场景。', correlationId);
  }

  const body = await readJson(request);
  assertAllowedProperties(body, ['clientRequestId']);
  if (!isUuid(body.clientRequestId)) {
    throw new RequestError(400, errorCodes.validation, 'clientRequestId 必须是 UUID。');
  }

  const idempotencyKey = `${conversation.conversationId}:${body.clientRequestId}`;
  const existingId = idempotentGenerations.get(idempotencyKey);
  if (existingId) {
    return sendJson(response, 202, toAcceptedView(generations.get(existingId)));
  }

  const turn = conversation.turns.find((candidate) => candidate.userMessageId === source.userMessageId);
  if (!turn) {
    throw new RequestError(500, errorCodes.internal, '原始用户消息不存在。');
  }

  const generation = newGeneration(conversation.conversationId, {
    clientRequestId: body.clientRequestId,
    modelId: source.modelId,
    content: source.requestContent
  }, scenario, correlationId, turn.userMessageId);
  generations.set(generation.generationId, generation);
  idempotentGenerations.set(idempotencyKey, generation.generationId);
  conversation.generationIds.push(generation.generationId);
  turn.activeGenerationId = generation.generationId;
  return sendJson(response, 202, toAcceptedView(generation));
}

function newGeneration(conversationId, request, scenario, correlationId, userMessageId = randomUUID()) {
  const generationId = randomUUID();
  const now = new Date().toISOString();
  const generation = {
    generationId,
    conversationId,
    userMessageId,
    clientRequestId: request.clientRequestId,
    modelId: request.modelId,
    requestContent: request.content,
    status: 'PENDING',
    outputText: '等待生成。',
    createdAt: now,
    updatedAt: now,
    scenario,
    events: [],
    appliedEventIds: new Set(),
    resetCursors: new Map(),
    eventPlanVersion: 1,
    nextSequence: 1
  };
  generation.events = buildEvents(generation, scenario, correlationId);
  generation.nextSequence = generation.events.length + 1;
  return generation;
}

function buildEvents(generation, scenario, correlationId) {
  const definitions = [];
  if (scenario === 'replay-reset') {
    definitions.push(['replay.reset', { snapshotUrl: statusUrl(generation.generationId) }]);
  }
  definitions.push(['generation.started', { status: 'STREAMING' }]);

  if (scenario === 'failed') {
    definitions.push(
      ['content.delta', { delta: '这是一段未完成的结果。' }],
      ['generation.failed', {
        status: 'FAILED',
        code: errorCodes.upstreamTimeout,
        message: 'Mock 场景模拟上游超时。',
        correlationId
      }]
    );
  } else if (scenario === 'interrupted') {
    definitions.push(
      ['content.delta', { delta: '这是一段被中断的结果。' }],
      ['generation.interrupted', {
        status: 'INTERRUPTED',
        code: errorCodes.upstreamInterrupted,
        message: 'Mock 场景模拟上游连接中断。',
        correlationId
      }]
    );
  } else {
    definitions.push(
      ['content.delta', { delta: '这是来自 Conversation Mock 的响应。' }],
      ['usage.updated', { promptTokens: 12, completionTokens: 8, totalTokens: 20 }],
      ['generation.completed', { status: 'SUCCEEDED' }]
    );
  }

  return definitions.map(([eventType, payload], index) => envelope(generation.generationId, index + 1, eventType, payload));
}

function stopGeneration(response, generation, correlationId) {
  if (['SUCCEEDED', 'FAILED', 'STOPPED', 'INTERRUPTED'].includes(generation.status)) {
    return sendError(response, 409, errorCodes.conflict, '终态生成不能再次停止。', correlationId);
  }

  const deliveredEvents = generation.events.filter((event) => generation.appliedEventIds.has(event.eventId));
  const stoppedEvent = allocateEnvelope(generation, 'generation.stopped', { status: 'STOPPED' });
  generation.events = [...deliveredEvents, stoppedEvent];
  generation.eventPlanVersion += 1;
  generation.status = 'STOPPED';
  generation.updatedAt = new Date().toISOString();
  return sendJson(response, 200, toGenerationView(generation));
}

async function streamEvents(request, response, generation) {
  const lastEventId = request.headers['last-event-id'];
  let replayEvents;
  let followsEventPlan = false;
  let replayPlanVersion = generation.eventPlanVersion;
  let lastSentSequence = 0;
  if (lastEventId) {
    const index = generation.events.findIndex((event) => event.eventId === lastEventId);
    if (index !== -1) {
      replayEvents = generation.events.slice(index + 1);
      followsEventPlan = true;
      lastSentSequence = generation.events[index].sequence;
    } else if (generation.resetCursors.has(lastEventId)) {
      const resetSequence = generation.resetCursors.get(lastEventId);
      replayEvents = generation.events.filter((event) => event.sequence > resetSequence);
      followsEventPlan = true;
      lastSentSequence = resetSequence;
    } else {
      const resetEvent = allocateEnvelope(generation, 'replay.reset', { snapshotUrl: statusUrl(generation.generationId) });
      const deliveredEvents = generation.events.filter((event) => generation.appliedEventIds.has(event.eventId));
      const pendingEvents = generation.events
        .filter((event) => !generation.appliedEventIds.has(event.eventId))
        .map((event) => allocateEnvelope(generation, event.eventType, event.payload));
      generation.events = [...deliveredEvents, ...pendingEvents];
      generation.eventPlanVersion += 1;
      generation.resetCursors.set(resetEvent.eventId, resetEvent.sequence);
      replayEvents = [resetEvent];
      lastSentSequence = resetEvent.sequence;
    }
    replayPlanVersion = generation.eventPlanVersion;
  }

  response.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    'X-Accel-Buffering': 'no',
    Connection: 'keep-alive'
  });

  if (replayEvents) {
    let index = 0;
    while (index < replayEvents.length) {
      if (followsEventPlan && replayPlanVersion !== generation.eventPlanVersion) {
        replayEvents = generation.events.filter((event) => event.sequence > lastSentSequence);
        replayPlanVersion = generation.eventPlanVersion;
        index = 0;
        if (replayEvents.length === 0) {
          break;
        }
      }
      const event = replayEvents[index];
      applyEventOnce(generation, event);
      response.write(`id: ${event.eventId}\nevent: ${event.eventType}\ndata: ${JSON.stringify(event)}\n\n`);
      lastSentSequence = event.sequence;
      index += 1;
      if (generation.scenario === 'slow' && index < replayEvents.length) {
        await delay(500);
      }
    }
    return response.end();
  }

  for (let index = 0; index < generation.events.length; index += 1) {
    const event = generation.events[index];
    applyEventOnce(generation, event);
    response.write(`id: ${event.eventId}\nevent: ${event.eventType}\ndata: ${JSON.stringify(event)}\n\n`);
    if (generation.scenario === 'slow' && index < generation.events.length - 1) {
      await delay(500);
    }
  }
  response.end();
}

function applyEventOnce(generation, event) {
  if (generation.appliedEventIds.has(event.eventId)) {
    return;
  }
  generation.appliedEventIds.add(event.eventId);
  if (generation.status === 'STOPPED' && event.eventType !== 'generation.stopped') {
    return;
  }
  if (event.eventType === 'generation.started') {
    generation.status = 'STREAMING';
    generation.outputText = '';
  } else if (event.eventType === 'content.delta') {
    generation.outputText += event.payload.delta;
  } else if (event.eventType === 'generation.completed') {
    generation.status = 'SUCCEEDED';
  } else if (event.eventType === 'generation.failed') {
    generation.status = 'FAILED';
    generation.error = event.payload;
  } else if (event.eventType === 'generation.stopped') {
    generation.status = 'STOPPED';
  } else if (event.eventType === 'generation.interrupted') {
    generation.status = 'INTERRUPTED';
    generation.error = event.payload;
  }
  generation.updatedAt = new Date().toISOString();
}

function envelope(generationId, sequence, eventType, payload) {
  return {
    eventId: randomUUID(),
    eventType,
    generationId,
    sequence,
    occurredAt: new Date().toISOString(),
    payloadVersion: 1,
    payload
  };
}

function allocateEnvelope(generation, eventType, payload) {
  const event = envelope(generation.generationId, generation.nextSequence, eventType, payload);
  generation.nextSequence += 1;
  return event;
}

function toConversationView(conversation) {
  return {
    conversationId: conversation.conversationId,
    title: conversation.title,
    createdAt: conversation.createdAt,
    archived: conversation.archived
  };
}

function toMessageViews(conversation) {
  return conversation.turns.flatMap((turn) => {
    const generation = generations.get(turn.activeGenerationId);
    return [
      {
        messageId: turn.userMessageId,
        role: 'USER',
        content: turn.content,
        completeness: 'COMPLETE',
        generationId: null,
        createdAt: turn.createdAt
      },
      {
        messageId: generation.generationId,
        role: 'ASSISTANT',
        content: toGenerationView(generation).content,
        completeness: generation.status === 'SUCCEEDED' ? 'COMPLETE' : 'PARTIAL',
        generationId: generation.generationId,
        createdAt: generation.createdAt
      }
    ];
  });
}

function toAcceptedView(generation) {
  return {
    userMessageId: generation.userMessageId,
    generationId: generation.generationId,
    statusUrl: statusUrl(generation.generationId),
    eventsUrl: `${statusUrl(generation.generationId)}/events`
  };
}

function toGenerationView(generation) {
  const text = generation.outputText || (generation.status === 'STOPPED' ? '生成已停止。' : '等待生成。');
  return {
    generationId: generation.generationId,
    conversationId: generation.conversationId,
    status: generation.status,
    content: {
      schemaVersion: 1,
      blocks: [{ type: 'text', text }]
    },
    createdAt: generation.createdAt,
    updatedAt: generation.updatedAt,
    ...(generation.error ? { error: {
      code: generation.error.code,
      message: generation.error.message,
      correlationId: generation.error.correlationId
    } } : {})
  };
}

function statusUrl(generationId) {
  return `/api/v1/generations/${generationId}`;
}

function validateGenerationRequest(body) {
  assertAllowedProperties(body, ['clientRequestId', 'modelId', 'content']);
  if (!isUuid(body.clientRequestId) || !isUuid(body.modelId)) {
    throw new RequestError(400, errorCodes.validation, 'clientRequestId 和 modelId 必须是 UUID。');
  }
  validateMessageContent(body.content);
}

function validateMessageContent(content) {
  if (!content || typeof content !== 'object' || Array.isArray(content)) {
    throw new RequestError(400, errorCodes.validation, 'content 必须是对象。');
  }
  assertAllowedProperties(content, ['schemaVersion', 'blocks']);
  if (content.schemaVersion !== 1 || !Array.isArray(content.blocks) || content.blocks.length < 1 || content.blocks.length > 100) {
    throw new RequestError(400, errorCodes.validation, 'content 必须使用 schemaVersion 1，并包含 1 至 100 个内容块。');
  }

  for (const block of content.blocks) {
    if (!block || typeof block !== 'object' || Array.isArray(block)) {
      throw new RequestError(400, errorCodes.validation, '内容块必须是对象。');
    }
    if (block.type === 'text') {
      assertAllowedProperties(block, ['type', 'text']);
      if (typeof block.text !== 'string' || block.text.length === 0) {
        throw new RequestError(400, errorCodes.validation, 'text 内容块必须包含非空文本。');
      }
    } else if (block.type === 'image_ref' || block.type === 'file_ref') {
      assertAllowedProperties(block, ['type', 'resourceId']);
      if (!isUuid(block.resourceId)) {
        throw new RequestError(400, errorCodes.validation, '引用内容块必须包含 UUID resourceId。');
      }
    } else {
      throw new RequestError(400, errorCodes.validation, '不支持该内容块类型。');
    }
  }
}

function assertAllowedProperties(value, allowed) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new RequestError(400, errorCodes.validation, '请求体必须是对象。');
  }
  if (Object.keys(value).some((key) => !allowed.includes(key))) {
    throw new RequestError(400, errorCodes.validation, '请求体包含未声明字段。');
  }
}

async function readJson(request) {
  const contentType = request.headers['content-type'] ?? '';
  if (!contentType.toLowerCase().startsWith('application/json')) {
    throw new RequestError(415, errorCodes.validation, '请求体必须使用 application/json。');
  }

  const chunks = [];
  for await (const chunk of request) {
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new RequestError(400, errorCodes.validation, '请求体不是有效 JSON。');
  }
}

function sendJson(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  response.end(JSON.stringify(body));
}

function sendError(response, status, code, message, correlationId) {
  sendJson(response, status, {
    code,
    message,
    correlationId
  });
}

function isUuid(value) {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function parsePort(value) {
  if (value === undefined) {
    return 4174;
  }
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0 || parsed > 65_535) {
    throw new Error('PORT 必须是 0 至 65535 的整数。');
  }
  return parsed;
}

function delay(milliseconds) {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, milliseconds));
}

class RequestError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
