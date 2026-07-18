import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  ConversationView,
  GenerationCreateRequest,
  GenerationView
} from '@autumn-wind/api-contracts';

import { HttpError } from '../../../lib/http-error';
import { conversationKeys, queryClient } from '../../../lib/query-client';
import type { ConversationClient } from '../api/conversation-client';
import {
  createGenerationUiState,
  generationReducer,
  replaceGenerationSnapshot
} from './generation-reducer';
import type { GenerationUiState } from './generation-state';

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
  activeGeneration?: GenerationUiState;
  submitting: boolean;
  error?: ConversationSessionError;
}

export interface UseConversationSessionOptions {
  conversationClient: ConversationClient;
  conversationId?: string;
  onConversationCreated?: (conversationId: string) => void;
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
      correlationId: error.correlationId
    };
  }

  return {
    code: 'AW-CONVERSATION-CLIENT-0001',
    message: '生成连接失败'
  };
}

function snapshotError(snapshot: GenerationView): ConversationSessionError | undefined {
  if (!snapshot.error) {
    return undefined;
  }

  return {
    code: snapshot.error.code,
    message: snapshot.error.message,
    correlationId: snapshot.error.correlationId
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
  onConversationCreated
}: UseConversationSessionOptions): ConversationSession {
  const [activeGeneration, setActiveGeneration] = useState<GenerationUiState>();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ConversationSessionError>();
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
    }
    previousRouteConversationIdRef.current = conversationId;
    conversationIdRef.current = conversationId;
  }, [conversationId]);

  const updateGeneration = useCallback((next: GenerationUiState) => {
    activeGenerationRef.current = next;
    setActiveGeneration(next);
  }, []);

  const consumeStream = useCallback(
    async (eventsUrl: string, generation: GenerationUiState, controller: AbortController) => {
      let current = generation;

      try {
        for await (const event of conversationClient.streamGeneration(
          eventsUrl,
          current.lastEventId,
          controller.signal
        )) {
          if (controller.signal.aborted) {
            return;
          }

          current = generationReducer(current, event);
          updateGeneration(current);

          if (event.eventType === 'replay.reset') {
            const refreshed = await conversationClient.getGeneration(current.generationId, controller.signal);
            current = replaceGenerationSnapshot(current, refreshed);
            updateGeneration(current);
            setError(snapshotError(refreshed));
          }
        }

        if (isTerminal(current.status)) {
          setError(undefined);
          setSubmitting(false);
          submittingRef.current = false;
          if (conversationIdRef.current) {
            invalidateConversationQueries(conversationIdRef.current);
          }
        } else {
          setError({ code: 'AW-CONVERSATION-CLIENT-0002', message: '生成连接已断开' });
          setSubmitting(false);
          submittingRef.current = false;
        }
      } catch (reason) {
        if (controller.signal.aborted) {
          return;
        }

        try {
          const refreshed = await conversationClient.getGeneration(current.generationId, controller.signal);
          current = replaceGenerationSnapshot(current, refreshed);
          updateGeneration(current);
          setError(snapshotError(refreshed) ?? publicError(reason));
        } catch (snapshotReason) {
          setError(publicError(snapshotReason));
        }
        setSubmitting(false);
        submittingRef.current = false;
      } finally {
        if (streamControllerRef.current === controller) {
          streamControllerRef.current = undefined;
        }
      }
    },
    [conversationClient, updateGeneration]
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

        let accepted;
        try {
          accepted = await conversationClient.createGeneration(activeConversationId, request, controller.signal);
        } catch (firstReason) {
          if (controller.signal.aborted || !isRetryableCreateError(firstReason)) {
            throw firstReason;
          }
          accepted = await conversationClient.createGeneration(activeConversationId, request, controller.signal);
        }

        const pending = pendingGeneration(accepted.generationId);
        updateGeneration(pending);
        invalidateConversationQueries(activeConversationId);
        await consumeStream(accepted.eventsUrl, pending, controller);
      } catch (reason) {
        if (!controller.signal.aborted) {
          setError(publicError(reason));
          setSubmitting(false);
          submittingRef.current = false;
        }
      }
    },
    [conversationClient, consumeStream, onConversationCreated, updateGeneration]
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

  return { submit, stop, activeGeneration, submitting, error };
}
