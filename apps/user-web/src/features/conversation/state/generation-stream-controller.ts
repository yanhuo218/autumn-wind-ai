import type {
  ConversationStreamEventV1,
  GenerationAcceptedView,
  GenerationView
} from '@autumn-wind/api-contracts';

import type { ConversationClient } from '../api/conversation-client';
import { HttpError } from '../../../lib/http-error';

const retryDelays = [250, 500, 1000, 2000, 4000];

type ConnectionState = 'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED';
type Sleep = (milliseconds: number) => Promise<void>;

export interface GenerationStreamControllerOptions {
  accepted: GenerationAcceptedView;
  client: ConversationClient;
  onEvent(event: ConversationStreamEventV1): void;
  onSnapshot(snapshot: GenerationView): void;
  onConnectionState(state: ConnectionState): void;
  signal: AbortSignal;
  sleep?: Sleep;
}

function defaultSleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function isTerminalEvent(event: ConversationStreamEventV1): boolean {
  return (
    event.eventType === 'generation.completed' ||
    event.eventType === 'generation.failed' ||
    event.eventType === 'generation.stopped' ||
    event.eventType === 'generation.interrupted'
  );
}

function isTerminalStatus(status: GenerationView['status']): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'STOPPED' || status === 'INTERRUPTED';
}

export async function consumeGenerationStream({
  accepted,
  client,
  onEvent,
  onSnapshot,
  onConnectionState,
  signal,
  sleep = defaultSleep
}: GenerationStreamControllerOptions): Promise<void> {
  let lastEventId: string | undefined;
  let retryAttempt = 0;
  let terminal = false;

  try {
    while (!signal.aborted && !terminal) {
      onConnectionState('CONNECTED');
      let shouldReconnect = false;
      let receivedEvent = false;
      let reconnectBlocked = false;

      try {
        for await (const event of client.streamGeneration(accepted.eventsUrl, lastEventId, signal)) {
          if (signal.aborted) {
            return;
          }

          lastEventId = event.eventId;
          receivedEvent = true;
          onEvent(event);

          if (isTerminalEvent(event)) {
            terminal = true;
            break;
          }

          if (event.eventType === 'replay.reset') {
            try {
              const snapshot = await client.getGenerationSnapshot(event.payload.snapshotUrl, signal);
              onSnapshot(snapshot);
              terminal = isTerminalStatus(snapshot.status);
            } catch {
              // 快照不可用时保留同步中的本地状态，并从 reset 光标重新订阅。
            }
            shouldReconnect = !terminal && !signal.aborted;
            break;
          }
        }
      } catch (reason) {
        const isClientError = reason instanceof HttpError && reason.status >= 400 && reason.status < 500;
        reconnectBlocked = isClientError;
        shouldReconnect = !signal.aborted && !terminal && !isClientError;
      }

      if (signal.aborted || terminal) {
        return;
      }

      if (reconnectBlocked) {
        return;
      }

      if (!receivedEvent && lastEventId === undefined && !shouldReconnect) {
        return;
      }

      shouldReconnect = true;
      if (retryAttempt >= retryDelays.length) {
        return;
      }

      onConnectionState('RECONNECTING');
      await sleep(retryDelays[retryAttempt]);
      retryAttempt += 1;
      if (!shouldReconnect) {
        return;
      }
    }
  } finally {
    onConnectionState('DISCONNECTED');
  }
}
