import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, before, test } from 'node:test';

const testDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(testDirectory, '..', '..');
const mockScript = resolve(projectRoot, 'scripts', 'mock-conversation-api.mjs');
const modelId = '22222222-2222-4222-8222-222222222222';

let child;
let baseUrl;

before(async () => {
  child = spawn(process.execPath, [mockScript], {
    cwd: projectRoot,
    env: { ...process.env, PORT: '0' },
    stdio: ['ignore', 'pipe', 'pipe']
  });

  baseUrl = await waitForReady(child);
});

after(async () => {
  if (!child || child.exitCode !== null) {
    return;
  }

  const exited = once(child, 'exit');
  let timer;
  const timeout = new Promise((resolveTimeout) => {
    timer = setTimeout(resolveTimeout, 2_000, 'timeout');
    timer.unref();
  });
  child.kill();
  const outcome = await Promise.race([exited, timeout]);
  clearTimeout(timer);
  if (outcome === 'timeout' && child.exitCode === null) {
    const forcedExit = once(child, 'exit');
    child.kill('SIGKILL');
    await forcedExit;
  }
});

test('重复 clientRequestId 返回同一个生成且只产生一条 started 事件', async () => {
  const conversation = await createConversation();
  const clientRequestId = '11111111-1111-4111-8111-111111111111';

  const first = await createGeneration(conversation.conversationId, clientRequestId);
  const second = await createGeneration(conversation.conversationId, clientRequestId);
  const events = await readSse(first.eventsUrl);

  assert.equal(first.generationId, second.generationId);
  assert.equal(first.eventsUrl, second.eventsUrl);
  assert.equal(events.filter((event) => event.eventType === 'generation.started').length, 1);
});

test('成功场景输出有序 SSE 并以 SUCCEEDED 结束', async () => {
  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID());
  const events = await readSse(accepted.eventsUrl);
  const snapshot = await requestJson(accepted.statusUrl);

  assert.deepEqual(events.map((event) => event.eventType), [
    'generation.started',
    'content.delta',
    'usage.updated',
    'generation.completed'
  ]);
  assert.deepEqual(events.map((event) => event.sequence), [1, 2, 3, 4]);
  assert.equal(snapshot.status, 'SUCCEEDED');
});

test('活动 slow SSE 显式停止后保留部分文本且不再完成', async () => {
  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID(), 'slow');
  const contentSeen = deferred();
  const stream = readSse(accepted.eventsUrl, undefined, (event) => {
    if (event.eventType === 'content.delta') {
      contentSeen.resolve(event.payload.delta);
    }
  });

  const partialText = await withTimeout(contentSeen.promise, 2_000, '未收到 slow 场景的部分文本。');

  const stopped = await requestJson(`/api/v1/generations/${accepted.generationId}/stop`, {
    method: 'POST'
  });
  const [events] = await Promise.all([stream, delay(1_700)]);
  const snapshot = await requestJson(accepted.statusUrl);

  assert.equal(stopped.status, 'STOPPED');
  assert.equal(events.some((event) => event.eventType === 'generation.completed'), false);
  assert.equal(events.filter((event) => event.eventType === 'generation.stopped').length, 1);
  assert.equal(events.at(-1).eventType, 'generation.stopped');
  assert.equal(events.at(-1).payload.status, 'STOPPED');
  assert.equal(snapshot.status, 'STOPPED');
  assert.equal(snapshot.content.blocks[0].text, partialText);
  assert.notEqual(snapshot.content.blocks[0].text, '生成已停止。');
});

test('重复订阅和 Last-Event-ID 重放不重复应用内容', async () => {
  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID());
  const allEvents = await readSse(accepted.eventsUrl);
  const firstSnapshot = await requestJson(accepted.statusUrl);
  await readSse(accepted.eventsUrl);
  const replayed = await readSse(accepted.eventsUrl, allEvents[0].eventId);
  const replayedSnapshot = await requestJson(accepted.statusUrl);

  assert.deepEqual(replayed.map((event) => event.sequence), [2, 3, 4]);
  assert.equal(replayedSnapshot.content.blocks[0].text, firstSnapshot.content.blocks[0].text);
});

test('replay-reset 场景首先通知客户端替换本地内容', async () => {
  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID(), 'replay-reset');
  const events = await readSse(accepted.eventsUrl);

  assert.equal(events[0].eventType, 'replay.reset');
  assert.equal(events[0].payload.snapshotUrl, accepted.statusUrl);
});

test('未知 Last-Event-ID 输出 replay.reset', async () => {
  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID());
  const original = await readSse(accepted.eventsUrl);
  const events = await readSse(accepted.eventsUrl, 'missing-event-id-000000000000');
  const afterReset = await readSse(accepted.eventsUrl, events[0].eventId);

  assert.equal(events.length, 1);
  assert.equal(events[0].eventType, 'replay.reset');
  assert.equal(events[0].payload.snapshotUrl, accepted.statusUrl);
  assert.ok(events[0].sequence > Math.max(...original.map((event) => event.sequence)));
  assert.deepEqual(afterReset, []);
});

test('活动 slow 生成的 reset 游标可继续接收重编号的剩余事件', async () => {
  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID(), 'slow');
  const contentSeen = deferred();
  const originalStream = readSse(accepted.eventsUrl, undefined, (event) => {
    if (event.eventType === 'content.delta') {
      contentSeen.resolve();
    }
  });
  await withTimeout(contentSeen.promise, 2_000, '未收到 slow 场景的 content.delta。');

  const resetEvents = await readSse(accepted.eventsUrl, 'active-missing-event-id-000000');
  const reset = resetEvents[0];
  const [afterReset] = await Promise.all([
    readSse(accepted.eventsUrl, reset.eventId),
    originalStream
  ]);
  const snapshot = await requestJson(accepted.statusUrl);

  assert.equal(resetEvents.length, 1);
  assert.equal(reset.eventType, 'replay.reset');
  assert.deepEqual(afterReset.map((event) => event.eventType), ['usage.updated', 'generation.completed']);
  assert.ok(afterReset.every((event) => event.sequence > reset.sequence));
  assert.ok(afterReset.every((event, index) => index === 0 || event.sequence > afterReset[index - 1].sequence));
  assert.equal(snapshot.status, 'SUCCEEDED');
});

test('slow reset 重放在 usage 后停止时切换为 generation.stopped', async () => {
  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID(), 'slow');
  const reset = (await readSse(accepted.eventsUrl, 'stop-replay-missing-event-000000'))[0];
  const usageSeen = deferred();
  const replay = readSse(accepted.eventsUrl, reset.eventId, (event) => {
    if (event.eventType === 'usage.updated') {
      usageSeen.resolve();
    }
  });
  await withTimeout(usageSeen.promise, 2_000, 'reset 重放未收到 usage.updated。');

  await requestJson(`/api/v1/generations/${accepted.generationId}/stop`, { method: 'POST' });
  const events = await replay;
  const snapshot = await requestJson(accepted.statusUrl);

  assert.equal(events.some((event) => event.eventType === 'generation.completed'), false);
  assert.equal(events.at(-1).eventType, 'generation.stopped');
  assert.equal(events.filter((event) => event.eventType === 'generation.stopped').length, 1);
  assert.equal(snapshot.status, 'STOPPED');
});

test('slow reset 重放在计划重置后切换到新事件且内容只应用一次', async () => {
  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID(), 'slow');
  const reset1 = (await readSse(accepted.eventsUrl, 'first-plan-missing-event-00000'))[0];
  const startedSeen = deferred();
  const firstReplay = readSse(accepted.eventsUrl, reset1.eventId, (event) => {
    if (event.eventType === 'generation.started') {
      startedSeen.resolve();
    }
  });
  await withTimeout(startedSeen.promise, 1_000, 'reset1 重放未收到 generation.started。');

  const reset2 = (await readSse(accepted.eventsUrl, 'second-plan-missing-event-0000'))[0];
  const [firstEvents, secondEvents] = await Promise.all([
    firstReplay,
    readSse(accepted.eventsUrl, reset2.eventId)
  ]);
  const snapshot = await requestJson(accepted.statusUrl);

  assert.deepEqual(firstEvents.map((event) => event.eventType), [
    'generation.started',
    'content.delta',
    'usage.updated',
    'generation.completed'
  ]);
  assert.ok(firstEvents.every((event, index) => index === 0 || event.sequence > firstEvents[index - 1].sequence));
  assert.ok(firstEvents.slice(1).every((event) => event.sequence > reset2.sequence));
  assert.deepEqual(firstEvents.slice(1).map((event) => event.eventId), secondEvents.map((event) => event.eventId));
  assert.equal(snapshot.status, 'SUCCEEDED');
  assert.equal(snapshot.content.blocks[0].text, '这是来自 Conversation Mock 的响应。');
});

test('首次订阅前停止 PENDING 生成只输出 generation.stopped', async () => {
  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID());

  const stopped = await requestJson(`/api/v1/generations/${accepted.generationId}/stop`, {
    method: 'POST'
  });
  const events = await readSse(accepted.eventsUrl);
  const snapshot = await requestJson(accepted.statusUrl);

  assert.equal(stopped.status, 'STOPPED');
  assert.deepEqual(events.map((event) => event.eventType), ['generation.stopped']);
  assert.equal(snapshot.status, 'STOPPED');
});

test('failed 场景以上游超时 DEPENDENCY 错误结束', async () => {
  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID(), 'failed');
  const events = await readSse(accepted.eventsUrl);
  const snapshot = await requestJson(accepted.statusUrl);

  assert.equal(events.at(-1).eventType, 'generation.failed');
  assert.equal(events.at(-1).payload.status, 'FAILED');
  assert.equal(events.at(-1).payload.code, 'AW-CONVERSATION-DEPENDENCY-0003');
  assert.match(events.at(-1).payload.message, /上游超时/);
  assert.equal(snapshot.status, 'FAILED');
  assert.equal(snapshot.error.code, 'AW-CONVERSATION-DEPENDENCY-0003');
  assert.match(snapshot.error.message, /上游超时/);
});

test('interrupted 场景以 INTERRUPTED 和稳定 DEPENDENCY 错误结束', async () => {
  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID(), 'interrupted');
  const events = await readSse(accepted.eventsUrl);
  const snapshot = await requestJson(accepted.statusUrl);

  assert.equal(events.at(-1).eventType, 'generation.interrupted');
  assert.equal(events.at(-1).payload.status, 'INTERRUPTED');
  assert.equal(events.at(-1).payload.code, 'AW-CONVERSATION-DEPENDENCY-0004');
  assert.equal(snapshot.status, 'INTERRUPTED');
  assert.equal(snapshot.error.code, 'AW-CONVERSATION-DEPENDENCY-0004');
});

test('未知资源返回公共错误结构且响应不泄露端点或凭据字段', async () => {
  const response = await fetch(`${baseUrl}/api/v1/generations/${randomUUID()}`);
  const body = await response.json();

  assert.equal(response.status, 404);
  assert.equal(body.code, 'AW-CONVERSATION-NOT_FOUND-0001');
  assert.equal(typeof body.message, 'string');
  assert.equal(body.correlationId, response.headers.get('x-correlation-id'));
  assert.deepEqual(findForbiddenKeys(body), []);

  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID(), 'failed');
  const events = await readSse(accepted.eventsUrl);
  const snapshot = await requestJson(accepted.statusUrl);
  assert.deepEqual(findForbiddenKeys({ accepted, events, snapshot }), []);
});

test('未知请求字段返回 400 VALIDATION', async () => {
  const response = await fetch(`${baseUrl}/api/v1/conversations`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify({ title: '非法请求', unknown: true })
  });
  const body = await response.json();

  assert.equal(response.status, 400);
  assert.equal(body.code, 'AW-CONVERSATION-VALIDATION-0001');
});

test('会话列表、详情、归档与 regenerate 路由保持契约状态', async () => {
  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID());
  await readSse(accepted.eventsUrl);

  const list = await requestJson('/api/v1/conversations');
  assert.ok(list.items.some((item) => item.conversationId === conversation.conversationId));

  const detail = await requestJson(`/api/v1/conversations/${conversation.conversationId}`);
  assert.ok(detail.generations.some((item) => item.generationId === accepted.generationId));

  const regenerated = await requestJson(`/api/v1/generations/${accepted.generationId}/regenerate`, {
    method: 'POST',
    body: JSON.stringify({ clientRequestId: randomUUID() })
  }, 202);
  assert.notEqual(regenerated.generationId, accepted.generationId);

  const terminalStop = await fetch(`${baseUrl}/api/v1/generations/${accepted.generationId}/stop`, {
    method: 'POST',
    headers: { accept: 'application/json' }
  });
  assert.equal(terminalStop.status, 409);
  assert.equal((await terminalStop.json()).code, 'AW-CONVERSATION-CONFLICT-0001');

  const archived = await fetch(`${baseUrl}/api/v1/conversations/${conversation.conversationId}`, {
    method: 'DELETE',
    headers: { accept: 'application/json' }
  });
  assert.equal(archived.status, 204);
  const archivedDetail = await requestJson(`/api/v1/conversations/${conversation.conversationId}`);
  assert.equal(archivedDetail.archived, true);

  const createAfterArchive = await fetch(`${baseUrl}/api/v1/conversations/${conversation.conversationId}/generations`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify(generationRequest(randomUUID()))
  });
  assert.equal(createAfterArchive.status, 409);
  assert.equal((await createAfterArchive.json()).code, 'AW-CONVERSATION-CONFLICT-0001');
});

async function waitForReady(processHandle) {
  let stderr = '';
  processHandle.stderr.setEncoding('utf8');
  processHandle.stderr.on('data', (chunk) => {
    stderr += chunk;
  });

  return new Promise((resolveReady, rejectReady) => {
    processHandle.stdout.setEncoding('utf8');
    processHandle.stdout.on('data', (chunk) => {
      const match = chunk.match(/Mock Conversation API listening at (http:\/\/127\.0\.0\.1:\d+)/);
      if (match) {
        resolveReady(match[1]);
      }
    });
    processHandle.once('exit', (code) => {
      rejectReady(new Error(`Mock 子进程在就绪前退出，退出码 ${code}。${stderr}`));
    });
    processHandle.once('error', rejectReady);
  });
}

async function createConversation() {
  return requestJson('/api/v1/conversations', {
    method: 'POST',
    body: JSON.stringify({ title: '契约测试会话' })
  }, 201);
}

async function createGeneration(conversationId, clientRequestId, scenario = 'success') {
  return requestJson(`/api/v1/conversations/${conversationId}/generations?scenario=${scenario}`, {
    method: 'POST',
    body: JSON.stringify(generationRequest(clientRequestId))
  }, 202);
}

function generationRequest(clientRequestId) {
  return {
    clientRequestId,
    modelId,
    content: {
      schemaVersion: 1,
      blocks: [{ type: 'text', text: '请生成一段测试文本。' }]
    }
  };
}

async function requestJson(path, options = {}, expectedStatus = 200) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      accept: 'application/json',
      ...(options.body ? { 'content-type': 'application/json' } : {}),
      ...options.headers
    }
  });
  assert.equal(response.status, expectedStatus);
  return response.json();
}

async function readSse(path, lastEventId, onEvent) {
  const response = await fetch(`${baseUrl}${path}`, {
    headers: {
      accept: 'text/event-stream',
      ...(lastEventId ? { 'Last-Event-ID': lastEventId } : {})
    }
  });
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /^text\/event-stream/);

  const events = [];
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    let boundary = buffer.match(/\r?\n\r?\n/);
    while (boundary) {
      const frame = buffer.slice(0, boundary.index);
      buffer = buffer.slice(boundary.index + boundary[0].length);
      if (frame) {
        const event = parseSseFrame(frame);
        events.push(event);
        onEvent?.(event);
      }
      boundary = buffer.match(/\r?\n\r?\n/);
    }
    if (done) {
      break;
    }
  }
  if (buffer.trim()) {
    const event = parseSseFrame(buffer.trim());
    events.push(event);
    onEvent?.(event);
  }
  return events;
}

function parseSseFrame(frame) {
    const lines = frame.split(/\r?\n/);
    const id = lines.find((line) => line.startsWith('id: '))?.slice(4);
    const eventType = lines.find((line) => line.startsWith('event: '))?.slice(7);
    const data = lines.find((line) => line.startsWith('data: '))?.slice(6);
    assert.ok(id);
    assert.ok(eventType);
    assert.ok(data);
    const envelope = JSON.parse(data);
    assert.equal(envelope.eventId, id);
    assert.equal(envelope.eventType, eventType);
    return envelope;
}

function deferred() {
  let resolvePromise;
  const promise = new Promise((resolve) => {
    resolvePromise = resolve;
  });
  return { promise, resolve: resolvePromise };
}

async function withTimeout(promise, milliseconds, message) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(message)), milliseconds);
    timer.unref();
  });
  try {
    return await Promise.race([promise, timeout]);
  } finally {
    clearTimeout(timer);
  }
}

function delay(milliseconds) {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, milliseconds));
}

function findForbiddenKeys(value, path = '$') {
  if (Array.isArray(value)) {
    return value.flatMap((item, index) => findForbiddenKeys(item, `${path}[${index}]`));
  }
  if (!value || typeof value !== 'object') {
    return [];
  }

  const forbidden = new Set(['apiKey', 'authorization', 'baseUrl', 'credential', 'credentialId', 'endpoint', 'endpointId', 'password', 'token']);
  return Object.entries(value).flatMap(([key, nested]) => [
    ...(forbidden.has(key) ? [`${path}.${key}`] : []),
    ...findForbiddenKeys(nested, `${path}.${key}`)
  ]);
}
