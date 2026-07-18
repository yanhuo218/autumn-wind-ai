import type { GenerationView, PublicErrorResponse } from '@autumn-wind/api-contracts';

export type GenerationStatus = GenerationView['status'];
export type PublicError = Pick<PublicErrorResponse, 'code' | 'message' | 'correlationId'>;

export interface UsageState {
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
}

export interface GenerationUiState {
  generationId: string;
  status: GenerationStatus | 'SYNCING';
  content: string;
  reasoning: string;
  usage: UsageState;
  lastEventId?: string;
  lastSequence: number;
  seenEventIds: ReadonlySet<string>;
  error?: PublicError;
}
