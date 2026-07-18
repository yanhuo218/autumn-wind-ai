import {
  isConversationDetailView,
  isConversationListView,
  isConversationView,
  isGenerationAcceptedView,
  isGenerationView,
  type ConversationDetailView,
  type ConversationListView,
  type ConversationStreamEventV1,
  type ConversationView,
  type GenerationAcceptedView,
  type GenerationCreateRequest,
  type GenerationView,
  type RegenerateRequest
} from '@autumn-wind/api-contracts';

import {
  assertApiPath,
  fetchEmpty,
  fetchJson,
  fetchResponse,
  protocolError
} from '../../../lib/fetch-json';
import { parseConversationEventStream } from './sse-parser';

export interface ConversationClient {
  listConversations(signal?: AbortSignal): Promise<ConversationListView>;
  getConversation(conversationId: string, signal?: AbortSignal): Promise<ConversationDetailView>;
  createConversation(title?: string, signal?: AbortSignal): Promise<ConversationView>;
  archiveConversation(conversationId: string, signal?: AbortSignal): Promise<void>;
  createGeneration(
    conversationId: string,
    request: GenerationCreateRequest,
    signal?: AbortSignal
  ): Promise<GenerationAcceptedView>;
  getGeneration(generationId: string, signal?: AbortSignal): Promise<GenerationView>;
  getGenerationSnapshot(snapshotUrl: string, signal?: AbortSignal): Promise<GenerationView>;
  streamGeneration(
    eventsUrl: string,
    lastEventId?: string,
    signal?: AbortSignal
  ): AsyncGenerator<ConversationStreamEventV1>;
  stopGeneration(generationId: string, signal?: AbortSignal): Promise<GenerationView>;
  regenerate(
    generationId: string,
    request: RegenerateRequest,
    signal?: AbortSignal
  ): Promise<GenerationAcceptedView>;
}

export type ConversationMockScenario =
  | 'success'
  | 'slow'
  | 'failed'
  | 'interrupted'
  | 'replay-reset'
  | 'disconnect-once';

export interface ConversationClientOptions {
  scenarioProvider?: () => ConversationMockScenario | undefined;
}

const conversationMockScenarios: ReadonlySet<string> = new Set([
  'success',
  'slow',
  'failed',
  'interrupted',
  'replay-reset',
  'disconnect-once'
]);

export function isConversationMockScenario(value?: string): value is ConversationMockScenario {
  return Boolean(value && conversationMockScenarios.has(value));
}

function jsonRequest(method: string, body: unknown, signal?: AbortSignal): RequestInit {
  return {
    method,
    signal,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  };
}

function generationPath(generationId: string): string {
  return `/api/v1/generations/${encodeURIComponent(generationId)}`;
}

function scenarioPath(
  path: string,
  scenarioProvider?: () => ConversationMockScenario | undefined
): string {
  const scenario = scenarioProvider?.();
  if (!isConversationMockScenario(scenario)) {
    return path;
  }

  const url = new URL(path, 'https://autumn-wind.invalid');
  url.searchParams.set('scenario', scenario);
  return `${url.pathname}${url.search}`;
}

function validateAcceptedLinks(value: GenerationAcceptedView): GenerationAcceptedView {
  assertApiPath(value.statusUrl);
  assertApiPath(value.eventsUrl);
  return value;
}

export function createConversationClient(
  fetchImpl: typeof fetch = fetch,
  options: ConversationClientOptions = {}
): ConversationClient {
  return {
    listConversations(signal) {
      return fetchJson(fetchImpl, '/api/v1/conversations', isConversationListView, { signal });
    },

    getConversation(conversationId, signal) {
      return fetchJson(
        fetchImpl,
        `/api/v1/conversations/${encodeURIComponent(conversationId)}`,
        isConversationDetailView,
        { signal }
      );
    },

    createConversation(title, signal) {
      return fetchJson(
        fetchImpl,
        '/api/v1/conversations',
        isConversationView,
        jsonRequest('POST', title === undefined ? {} : { title }, signal)
      );
    },

    archiveConversation(conversationId, signal) {
      return fetchEmpty(fetchImpl, `/api/v1/conversations/${encodeURIComponent(conversationId)}`, {
        method: 'DELETE',
        signal
      });
    },

    async createGeneration(conversationId, request, signal) {
      const value = await fetchJson(
        fetchImpl,
        scenarioPath(
          `/api/v1/conversations/${encodeURIComponent(conversationId)}/generations`,
          options.scenarioProvider
        ),
        isGenerationAcceptedView,
        jsonRequest('POST', request, signal)
      );
      return validateAcceptedLinks(value);
    },

    getGeneration(generationId, signal) {
      return fetchJson(fetchImpl, generationPath(generationId), isGenerationView, { signal });
    },

    async getGenerationSnapshot(snapshotUrl, signal) {
      assertApiPath(snapshotUrl);
      return fetchJson(fetchImpl, snapshotUrl, isGenerationView, { signal });
    },

    async *streamGeneration(eventsUrl, lastEventId, signal) {
      assertApiPath(eventsUrl);
      const headers = new Headers();
      if (lastEventId !== undefined) {
        headers.set('Last-Event-ID', lastEventId);
      }
      const response = await fetchResponse(
        fetchImpl,
        eventsUrl,
        { signal, headers },
        'text/event-stream'
      );
      if (!response.body) {
        throw protocolError(response.status);
      }

      yield* parseConversationEventStream(response.body);
    },

    stopGeneration(generationId, signal) {
      return fetchJson(fetchImpl, `${generationPath(generationId)}/stop`, isGenerationView, {
        method: 'POST',
        signal
      });
    },

    async regenerate(generationId, request, signal) {
      const value = await fetchJson(
        fetchImpl,
        scenarioPath(`${generationPath(generationId)}/regenerate`, options.scenarioProvider),
        isGenerationAcceptedView,
        jsonRequest('POST', request, signal)
      );
      return validateAcceptedLinks(value);
    }
  };
}
