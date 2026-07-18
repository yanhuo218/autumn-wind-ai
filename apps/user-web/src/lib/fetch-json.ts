import { isPublicErrorResponse } from '@autumn-wind/api-contracts';

import { HttpError } from './http-error';

export type JsonGuard<T> = (value: unknown) => value is T;

export function protocolError(status = 502): HttpError {
  return new HttpError(status, 'PROTOCOL_ERROR', '服务响应协议无效');
}

export function assertApiPath(url: string): void {
  const base = new URL('https://autumn-wind.invalid');
  if (
    url.trim() !== url ||
    !url.startsWith('/') ||
    url.startsWith('//') ||
    url.includes('\\') ||
    url.includes('#')
  ) {
    throw protocolError();
  }

  let parsed: URL;
  try {
    parsed = new URL(url, base);
  } catch {
    throw protocolError();
  }

  if (parsed.origin !== base.origin || !parsed.pathname.startsWith('/api/v1/')) {
    throw protocolError();
  }
}

function mediaType(contentType: string | null): string {
  return contentType?.split(';', 1)[0].trim().toLowerCase() ?? '';
}

function isJsonMediaType(contentType: string | null): boolean {
  const type = mediaType(contentType);
  return type === 'application/json' || (type.startsWith('application/') && type.endsWith('+json'));
}

function isEventStreamMediaType(contentType: string | null): boolean {
  return mediaType(contentType) === 'text/event-stream';
}

async function parseJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    throw protocolError(response.status);
  }
}

async function toHttpError(response: Response): Promise<HttpError> {
  const value = await parseJson(response);
  if (!isPublicErrorResponse(value)) {
    return protocolError(response.status);
  }

  return new HttpError(response.status, value.code, value.message, value.correlationId);
}

export async function fetchResponse(
  fetchImpl: typeof fetch,
  url: string,
  init: RequestInit = {},
  accept = 'application/json'
): Promise<Response> {
  assertApiPath(url);
  const headers = new Headers(init.headers);
  headers.set('Accept', accept);

  const response = await fetchImpl(url, {
    ...init,
    credentials: 'include',
    headers
  });

  if (!response.ok) {
    if (!isJsonMediaType(response.headers.get('Content-Type'))) {
      throw protocolError(response.status);
    }
    throw await toHttpError(response);
  }

  if (response.status !== 204) {
    const validMediaType = accept === 'text/event-stream'
      ? isEventStreamMediaType(response.headers.get('Content-Type'))
      : isJsonMediaType(response.headers.get('Content-Type'));
    if (!validMediaType) {
      throw protocolError(response.status);
    }
  }

  return response;
}

export async function fetchJson<T>(
  fetchImpl: typeof fetch,
  url: string,
  guard: JsonGuard<T>,
  init: RequestInit = {}
): Promise<T> {
  const response = await fetchResponse(fetchImpl, url, init);
  const value = await parseJson(response);
  if (!guard(value)) {
    throw protocolError(response.status);
  }

  return value;
}

export async function fetchEmpty(
  fetchImpl: typeof fetch,
  url: string,
  init: RequestInit = {}
): Promise<void> {
  await fetchResponse(fetchImpl, url, init);
}
