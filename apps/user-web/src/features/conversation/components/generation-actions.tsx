import { RotateCcw } from 'lucide-react';

import { IconButton } from '../../../components/icon-button';
import type { GenerationUiState, PublicError } from '../state/generation-state';
import { normalizePublicSummary } from './generation-state-rail';

type RecoverableStatus = Extract<
  GenerationUiState['status'],
  'FAILED' | 'INTERRUPTED' | 'STOPPED'
>;

const recoverableStatuses: ReadonlySet<GenerationUiState['status']> = new Set([
  'FAILED',
  'INTERRUPTED',
  'STOPPED'
]);
const correlationIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export interface GenerationActionsProps {
  status: GenerationUiState['status'];
  error?: PublicError;
  onRegenerate: () => Promise<void> | void;
}

function isRecoverableStatus(
  status: GenerationUiState['status']
): status is RecoverableStatus {
  return recoverableStatuses.has(status);
}

function publicCorrelationId(correlationId?: string): string | undefined {
  return correlationId && correlationIdPattern.test(correlationId)
    ? correlationId
    : undefined;
}

export function GenerationActions({
  status,
  error,
  onRegenerate
}: GenerationActionsProps) {
  if (!isRecoverableStatus(status)) {
    return null;
  }

  const showErrorDetails = status === 'FAILED' || status === 'INTERRUPTED';
  const correlationId = publicCorrelationId(error?.correlationId);

  return (
    <div aria-label="生成恢复操作" className="aw-generation-actions" role="group">
      {showErrorDetails ? (
        <div className="aw-generation-actions__error">
          <p className="aw-generation-actions__summary">
            {normalizePublicSummary(error?.message)}
          </p>
          {correlationId ? (
            <p className="aw-generation-actions__correlation-id">
              关联 ID：{correlationId}
            </p>
          ) : null}
        </div>
      ) : null}
      <IconButton label="重新生成" onClick={() => void onRegenerate()}>
        <RotateCcw size={16} strokeWidth={1.8} />
      </IconButton>
    </div>
  );
}
