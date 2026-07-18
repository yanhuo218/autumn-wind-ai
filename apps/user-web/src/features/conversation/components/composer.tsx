import { Send, Square } from 'lucide-react';
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';

import { IconButton } from '../../../components/icon-button';

export interface ComposerProps {
  modelId?: string;
  text?: string;
  submitting?: boolean;
  onSubmit: (input: { text: string; modelId: string }) => Promise<void> | void;
  onStop?: () => Promise<void> | void;
}

export function Composer({
  modelId = '',
  text = '',
  submitting = false,
  onSubmit,
  onStop
}: ComposerProps) {
  const [value, setValue] = useState(text);
  const [localSubmitting, setLocalSubmitting] = useState(false);
  const submittingRef = useRef(false);

  useEffect(() => {
    setValue(text);
  }, [text]);

  const isSubmitting = submitting || localSubmitting;
  const canSubmit = Boolean(modelId.trim() && value.trim() && !isSubmitting);

  async function submit() {
    if (!canSubmit || submittingRef.current) return;
    submittingRef.current = true;
    setLocalSubmitting(true);
    try {
      await onSubmit({ text: value, modelId });
    } finally {
      submittingRef.current = false;
      setLocalSubmitting(false);
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void submit();
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (
      event.key === 'Enter' &&
      !event.shiftKey &&
      !event.nativeEvent.isComposing
    ) {
      event.preventDefault();
      void submit();
    }
  }

  return (
    <form className="aw-composer" onSubmit={handleSubmit}>
      <textarea
        aria-label="消息输入"
        className="aw-composer__textarea"
        disabled={isSubmitting}
        onChange={(event) => setValue(event.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="输入消息"
        value={value}
      />
      {isSubmitting ? (
        <IconButton
          aria-label="停止"
          className="aw-composer__action"
          label="停止"
          onClick={() => void onStop?.()}
        >
          <Square size={18} strokeWidth={1.8} />
        </IconButton>
      ) : (
        <IconButton
          aria-label="发送"
          className="aw-composer__action"
          disabled={!canSubmit}
          label="发送"
          type="submit"
        >
          <Send size={18} strokeWidth={1.8} />
        </IconButton>
      )}
    </form>
  );
}
