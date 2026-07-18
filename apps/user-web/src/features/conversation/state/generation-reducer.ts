import type {
  ConversationStreamEventV1,
  GenerationView,
  MessageContent,
  PublicErrorResponse
} from '@autumn-wind/api-contracts';

import type { GenerationUiState, PublicError, UsageState } from './generation-state';

function contentText(content: MessageContent): string {
  return content.blocks
    .filter((block) => block.type === 'text')
    .map((block) => block.text)
    .join('');
}

function publicError(error: PublicErrorResponse | null | undefined): PublicError | undefined {
  if (!error) {
    return undefined;
  }

  return {
    code: error.code,
    message: error.message,
    correlationId: error.correlationId
  };
}

function emptyUsage(): UsageState {
  return {
    promptTokens: null,
    completionTokens: null,
    totalTokens: null
  };
}

function isTerminalStatus(status: GenerationUiState['status']): boolean {
  return (
    status === 'SUCCEEDED' ||
    status === 'FAILED' ||
    status === 'STOPPED' ||
    status === 'INTERRUPTED'
  );
}

export function createGenerationUiState(snapshot: GenerationView): GenerationUiState {
  return {
    generationId: snapshot.generationId,
    status: snapshot.status,
    content: contentText(snapshot.content),
    reasoning: '',
    usage: emptyUsage(),
    lastSequence: 0,
    seenEventIds: new Set(),
    error: publicError(snapshot.error)
  };
}

export function replaceGenerationSnapshot(
  state: GenerationUiState,
  snapshot: GenerationView
): GenerationUiState {
  if (snapshot.generationId !== state.generationId) {
    return state;
  }

  const replacement = createGenerationUiState(snapshot);
  return {
    ...replacement,
    lastEventId: state.lastEventId,
    lastSequence: state.lastSequence,
    seenEventIds: state.seenEventIds
  };
}

export function generationReducer(
  state: GenerationUiState,
  event: ConversationStreamEventV1
): GenerationUiState {
  if (
    event.generationId !== state.generationId ||
    isTerminalStatus(state.status) ||
    state.seenEventIds.has(event.eventId) ||
    event.sequence <= state.lastSequence
  ) {
    return state;
  }

  const baseState: GenerationUiState = {
    ...state,
    lastEventId: event.eventId,
    lastSequence: event.sequence,
    seenEventIds: new Set(state.seenEventIds).add(event.eventId)
  };

  switch (event.eventType) {
    case 'generation.started':
      return { ...baseState, status: event.payload.status, error: undefined };
    case 'reasoning.delta':
      return { ...baseState, reasoning: state.reasoning + event.payload.delta };
    case 'content.delta':
      return { ...baseState, content: state.content + event.payload.delta };
    case 'content.checkpoint':
      return { ...baseState, content: contentText(event.payload.content) };
    case 'usage.updated':
      return { ...baseState, usage: { ...event.payload } };
    case 'generation.completed':
      return { ...baseState, status: event.payload.status, error: undefined };
    case 'generation.failed':
      return {
        ...baseState,
        status: event.payload.status,
        error: {
          code: event.payload.code,
          message: event.payload.message,
          correlationId: event.payload.correlationId
        }
      };
    case 'generation.stopped':
      return { ...baseState, status: event.payload.status, error: undefined };
    case 'generation.interrupted':
      return {
        ...baseState,
        status: event.payload.status,
        error: {
          code: event.payload.code,
          message: event.payload.message,
          correlationId: event.payload.correlationId
        }
      };
    case 'stream.heartbeat':
      return baseState;
    case 'replay.reset':
      return { ...baseState, status: 'SYNCING' };
  }
}
