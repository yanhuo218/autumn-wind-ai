import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  GenerationAcceptedView,
  ConversationView,
  GenerationCreateRequest,
  GenerationView
} from '@autumn-wind/api-contracts';

import { HttpError } from '../../../lib/http-error';
import { conversationKeys, queryClient } from '../../../lib/query-client';
import type { ConversationClient } from '../api/conversation-client';
import {
  generationReducer,
  replaceGenerationSnapshot
} from './generation-reducer';
import type { GenerationUiState } from './generation-state';
import { consumeGenerationStream } from './generation-stream-controller';

export interface SubmitMessageInput {
  text: string;
  modelId: string;
}

export interface ConversationSessionError {
  code: string;
  message: string;
  correlationId?: string;
}

export interface ConversationSession {
  submit(input: SubmitMessageInput): Promise<void>;
  stop(): Promise<void>;
  regenerate(generationId: string): Promise<void>;
  activeGeneration?: GenerationUiState;
  connectionState: 'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED';
  submitting: boolean;
  error?: ConversationSessionError;
}

export interface UseConversationSessionOptions {
  conversationClient: ConversationClient;
  conversationId?: string;
  onConversationCreated?: (conversationId: string) => void;
  streamSleep?: (milliseconds: number) => Promise<void>;
}

function isTerminal(status: GenerationUiState['status']): boolean {
  return (
    status === 'SUCCEEDED' ||
    status === 'FAILED' ||
    status === 'STOPPED' ||
    status === 'INTERRUPTED'
  );
}

function publicError(error: unknown): ConversationSessionError {
  if (error instanceof HttpError) {
    return {
      code: error.code,
      message: error.message,
      correlationId: publicCorrelationId(error.correlationId)
    };
  }

  return {
    code: 'AW-CONVERSATION-CLIENT-0001',
    message: '生成连接失败'
  };
}

const correlationIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function publicCorrelationId(correlationId?: string): string | undefined {
  return correlationId && correlationIdPattern.test(correlationId)
    ? correlationId
    : undefined;
}

function snapshotError(snapshot: GenerationView): ConversationSessionError | undefined {
  if (!snapshot.error) {
    return undefined;
  }

  return {
    code: snapshot.error.code,
    message: snapshot.error.message,
    correlationId: publicCorrelationId(snapshot.error.correlationId)
  };
}

function isRetryableCreateError(error: unknown): boolean {
  return !(error instanceof HttpError);
}

function pendingGeneration(generationId: string): GenerationUiState {
  return {
    generationId,
    status: 'PENDING',
    content: '',
    reasoning: '',
    usage: {
      promptTokens: null,
      completionTokens: null,
      totalTokens: null
    },
    lastSequence: 0,
    seenEventIds: new Set()
  };
}

function invalidateConversationQueries(conversationId: string): void {
  void queryClient.invalidateQueries({ queryKey: conversationKeys.all });
  void queryClient.invalidateQueries({ queryKey: conversationKeys.detail(conversationId) });
}

export function useConversationSession({
  conversationClient,
  conversationId,
  onConversationCreated,
  streamSleep
}: UseConversationSessionOptions): ConversationSession {
  const [activeGeneration, setActiveGeneration] = useState<GenerationUiState>();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ConversationSessionError>();
  const [connectionState, setConnectionState] = useState<'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED'>('DISCONNECTED');
  const activeGenerationRef = useRef<GenerationUiState | undefined>(undefined);
  const submittingRef = useRef(false);
  const conversationIdRef = useRef(conversationId);
  const previousRouteConversationIdRef = useRef(conversationId);
  const streamControllerRef = useRef<AbortController | undefined>(undefined);

  useEffect(() => {
    const previousRouteConversationId = previousRouteConversationIdRef.current;
    if (previousRouteConversationId !== conversationId && previousRouteConversationId !== undefined) {
      streamControllerRef.current?.abort();
      streamControllerRef.current = undefined;
      activeGenerationRef.current = undefined;
      submittingRef.current = false;
      setActiveGeneration(undefined);
      setSubmitting(false);
      setError(undefined);
      setConnectionState('DISCONNECTED');
    }
    previousRouteConversationIdRef.current = conversationId;
    conversationIdRef.current = conversationId;
  }, [conversationId]);

  const updateGeneration = useCallback((next: GenerationUiState) => {
    activeGenerationRef.current = next;
    setActiveGeneration(next);
  }, []);

  const consumeAccepted = useCallback(
    async (accepted: GenerationAcceptedView, controller: AbortController) => {
      await consumeGenerationStream({
        accepted,
        client: conversationClient,
        signal: controller.signal,
        sleep: streamSleep,
        onEvent: (event) => {
          const current = activeGenerationRef.current;
          if (!current || current.generationId !== event.generationId) {
            return;
          }
          updateGeneration(generationReducer(current, event));
        },
        onSnapshot: (snapshot) => {
          const current = activeGenerationRef.current;
          if (!current || current.generationId !== snapshot.generationId) {
            return;
          }
          updateGeneration(replaceGenerationSnapshot(current, snapshot));
          setError(snapshotError(snapshot));
        },
        onConnectionState: setConnectionState
      });

      if (controller.signal.aborted) {
        return;
      }

      const current = activeGenerationRef.current;
      if (current && isTerminal(current.status)) {
        setError(current.error ? {
          code: current.error.code,
          message: current.error.message,
          correlationId: publicCorrelationId(current.error.correlationId)
        } : undefined);
        if (current.status === 'SUCCEEDED' || current.status === 'STOPPED') {
          setError(undefined);
        }
        if (conversationIdRef.current) {
          invalidateConversationQueries(conversationIdRef.current);
        }
      } else {
        setError({ code: 'AW-CONVERSATION-CLIENT-0002', message: '生成连接已断开' });
      }
      setSubmitting(false);
      submittingRef.current = false;
      if (streamControllerRef.current === controller) {
        streamControllerRef.current = undefined;
      }
    },
    [conversationClient, streamSleep, updateGeneration]
  );

  const submit = useCallback(
    async ({ text, modelId }: SubmitMessageInput) => {
      const trimmedText = text.trim();
      if (!trimmedText || !modelId || submittingRef.current) {
        return;
      }

      submittingRef.current = true;
      setSubmitting(true);
      setError(undefined);

      const controller = new AbortController();
      streamControllerRef.current = controller;
      const clientRequestId = crypto.randomUUID();
      const request: GenerationCreateRequest = {
        clientRequestId,
        modelId,
        content: {
          schemaVersion: 1,
          blocks: [{ type: 'text', text: trimmedText }]
        }
      };

      try {
        let activeConversationId = conversationIdRef.current;
        if (!activeConversationId) {
          const conversation: ConversationView = await conversationClient.createConversation(undefined, controller.signal);
          activeConversationId = conversation.conversationId;
          conversationIdRef.current = activeConversationId;
          onConversationCreated?.(activeConversationId);
        }

        if (controller.signal.aborted) {
          return;
        }

        let accepted;
        try {
          accepted = await conversationClient.createGeneration(activeConversationId, request, controller.signal);
        } catch (firstReason) {
          if (controller.signal.aborted || !isRetryableCreateError(firstReason)) {
            throw firstReason;
          }
          accepted = await conversationClient.createGeneration(activeConversationId, request, controller.signal);
        }

        if (controller.signal.aborted) {
          return;
        }

        const pending = pendingGeneration(accepted.generationId);
        updateGeneration(pending);
        invalidateConversationQueries(activeConversationId);
        await consumeAccepted(accepted, controller);
      } catch (reason) {
        if (!controller.signal.aborted) {
          setError(publicError(reason));
          setSubmitting(false);
          submittingRef.current = false;
        }
      }
    },
    [conversationClient, consumeAccepted, onConversationCreated, updateGeneration]
  );

  const regenerate = useCallback(
    async (generationId: string) => {
      if (submittingRef.current || !conversationIdRef.current) {
        return;
      }

      submittingRef.current = true;
      setSubmitting(true);
      setError(undefined);
      streamControllerRef.current?.abort();

      const controller = new AbortController();
      streamControllerRef.current = controller;
      try {
        const accepted = await conversationClient.regenerate(
          generationId,
          { clientRequestId: crypto.randomUUID() },
          controller.signal
        );
        if (controller.signal.aborted) {
          return;
        }
        updateGeneration(pendingGeneration(accepted.generationId));
        invalidateConversationQueries(conversationIdRef.current);
        await consumeAccepted(accepted, controller);
      } catch (reason) {
        if (!controller.signal.aborted) {
          setError(publicError(reason));
          setSubmitting(false);
          submittingRef.current = false;
          streamControllerRef.current = undefined;
        }
      }
    },
    [conversationClient, consumeAccepted, updateGeneration]
  );

  const stop = useCallback(async () => {
    const generation = activeGenerationRef.current;
    const controller = streamControllerRef.current;
    if (!generation || isTerminal(generation.status) || !conversationIdRef.current) {
      return;
    }

    try {
      const snapshot = await conversationClient.stopGeneration(
        generation.generationId,
        controller?.signal
      );
      updateGeneration(replaceGenerationSnapshot(generation, snapshot));
      setError(snapshotError(snapshot));
      invalidateConversationQueries(conversationIdRef.current);
    } catch (reason) {
      if (!controller?.signal.aborted) {
        setError(publicError(reason));
      }
    } finally {
      controller?.abort();
      streamControllerRef.current = undefined;
      setSubmitting(false);
      submittingRef.current = false;
    }
  }, [conversationClient, updateGeneration]);

  useEffect(() => {
    return () => {
      streamControllerRef.current?.abort();
      streamControllerRef.current = undefined;
    };
  }, []);

  return { submit, stop, regenerate, activeGeneration, connectionState, submitting, error };
}
