import {
  Check,
  CircleAlert,
  Clock3,
  LoaderCircle,
  RotateCcw,
  Square,
} from 'lucide-react';

import type { GenerationStatus } from '../state/generation-state';

type RailStatus = GenerationStatus | 'SYNCING';

export const PUBLIC_SUMMARY_MAX_LENGTH = 120;
const PUBLIC_SUMMARY_FALLBACK = '生成失败';
const sensitiveSummaryPatterns = [
  /(?:https?|ftp):\/\/\S+/i,
  /(?:^|[\s(])(?:www\.)\S+/i,
  /(?:^|[\s(（])(?:[A-Za-z]:[\\/]|\\\\|\/)\S*/i,
  /\b(?:bearer|token|api[\s_-]*key|cookie|authorization|password|passwd|secret)\b/i,
  /\b(?:stack\s*trace|traceback)\b/i,
  /(?:^|\s)at\s+\S+/i
];

export function normalizePublicSummary(summary?: string): string {
  const normalized = (summary ?? '')
    .replace(/[\u0000-\u001f\u007f-\u009f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  if (
    !normalized ||
    sensitiveSummaryPatterns.some((pattern) => pattern.test(normalized))
  ) {
    return PUBLIC_SUMMARY_FALLBACK;
  }

  return normalized.slice(0, PUBLIC_SUMMARY_MAX_LENGTH);
}

export interface GenerationStateRailProps {
  status: RailStatus;
  errorSummary?: string;
  contentDelta?: string;
  className?: string;
}

const statusMeta: Record<
  RailStatus,
  { label: string; icon: typeof Clock3 }
> = {
  PENDING: { label: '等待生成', icon: Clock3 },
  STREAMING: { label: '正在生成', icon: LoaderCircle },
  SUCCEEDED: { label: '生成完成', icon: Check },
  STOPPED: { label: '已停止', icon: Square },
  INTERRUPTED: { label: '生成中断', icon: RotateCcw },
  FAILED: { label: '生成失败', icon: CircleAlert },
  SYNCING: { label: '正在同步', icon: LoaderCircle }
};

const busyStatuses: ReadonlySet<RailStatus> = new Set([
  'PENDING',
  'STREAMING',
  'SYNCING'
]);

export function GenerationStateRail({
  status,
  errorSummary,
  className
}: GenerationStateRailProps) {
  const meta = statusMeta[status];
  const Icon = meta.icon;
  const label = status === 'FAILED'
    ? normalizePublicSummary(errorSummary)
    : meta.label;
  const isBusy = busyStatuses.has(status);
  const classes = [
    'aw-state-rail',
    `aw-state-rail--${status.toLowerCase()}`,
    className
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div
      aria-busy={isBusy}
      className={`aw-state-rail-container aw-state-rail-container--${status.toLowerCase()}`}
      data-status={status}
      data-testid="generation-state-rail-container"
    >
      <div
        aria-atomic="true"
        aria-live="polite"
        className={classes}
        role="status"
      >
        <span aria-hidden="true" className="aw-state-rail__icon">
          <Icon size={16} strokeWidth={1.8} />
        </span>
        <span className="aw-state-rail__label">
          {status === 'FAILED' && label !== PUBLIC_SUMMARY_FALLBACK
            ? `${meta.label}：${label}`
            : label}
        </span>
        {status === 'STREAMING' ? (
          <span
            aria-hidden="true"
            className="aw-state-rail__marker"
            data-testid="generation-state-rail-marker"
          />
        ) : null}
      </div>
    </div>
  );
}
