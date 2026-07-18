import {
  isConversationStreamEvent,
  type ConversationStreamEventV1
} from '@autumn-wind/api-contracts';

const protocolErrorMessage = '事件流协议无效';

function parseFrame(frame: string): ConversationStreamEventV1 | undefined {
  const dataLines: string[] = [];

  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith(':')) {
      continue;
    }

    const separatorIndex = line.indexOf(':');
    const field = separatorIndex === -1 ? line : line.slice(0, separatorIndex);
    let value = separatorIndex === -1 ? '' : line.slice(separatorIndex + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }

    if (field === 'data') {
      dataLines.push(value);
    }
  }

  if (dataLines.length === 0) {
    return undefined;
  }

  let value: unknown;
  try {
    value = JSON.parse(dataLines.join('\n'));
  } catch {
    throw new Error(protocolErrorMessage);
  }

  if (!isConversationStreamEvent(value)) {
    throw new Error(protocolErrorMessage);
  }

  return value;
}

function decodeChunk(decoder: TextDecoder, chunk?: Uint8Array, stream = true): string {
  try {
    return chunk === undefined ? decoder.decode() : decoder.decode(chunk, { stream });
  } catch {
    throw new Error(protocolErrorMessage);
  }
}

export async function* parseConversationEventStream(
  stream: ReadableStream<Uint8Array>
): AsyncGenerator<ConversationStreamEventV1> {
  const reader = stream.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let buffer = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        buffer += decodeChunk(decoder);
        break;
      }

      buffer += decodeChunk(decoder, value);
      let boundary = buffer.match(/\r?\n\r?\n/);

      while (boundary?.index !== undefined) {
        const frame = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        const parsedEvent = parseFrame(frame);
        if (parsedEvent) {
          yield parsedEvent;
        }
        boundary = buffer.match(/\r?\n\r?\n/);
      }
    }

    if (buffer.length > 0) {
      const parsedEvent = parseFrame(buffer);
      if (parsedEvent) {
        yield parsedEvent;
      }
    }
  } finally {
    reader.releaseLock();
  }
}
