import { ChevronDown, FileText, Image as ImageIcon, Type } from 'lucide-react';
import { Select } from 'radix-ui';

import type { ModelView } from '@autumn-wind/api-contracts';

export interface ModelSelectorProps {
  models: ModelView[];
  value?: string;
  onValueChange: (modelId: string) => void;
  disabled?: boolean;
}

function CapabilityIcons({ model }: { model: ModelView }) {
  return (
    <span className="aw-model-selector__capabilities" aria-hidden="true">
      <span data-testid="model-capability-text">
        <Type aria-hidden="true" size={14} strokeWidth={1.8} />
      </span>
      {model.capabilities.inputModalities.includes('IMAGE') ? (
        <span data-testid="model-capability-image">
          <ImageIcon aria-hidden="true" size={14} strokeWidth={1.8} />
        </span>
      ) : null}
      {model.capabilities.inputModalities.includes('FILE') ? (
        <span data-testid="model-capability-file">
          <FileText aria-hidden="true" size={14} strokeWidth={1.8} />
        </span>
      ) : null}
    </span>
  );
}

export function ModelSelector({ models, value, onValueChange, disabled = false }: ModelSelectorProps) {
  return (
    <Select.Root value={value ?? ''} onValueChange={onValueChange} disabled={disabled || models.length === 0}>
      <Select.Trigger className="aw-model-selector" aria-label="选择模型">
        <span data-testid="model-selector-label" className="aw-model-selector__label">
          <Select.Value placeholder="选择模型" />
        </span>
        <Select.Icon aria-hidden="true">
          <ChevronDown size={16} strokeWidth={1.8} />
        </Select.Icon>
      </Select.Trigger>
      <Select.Portal>
        <Select.Content className="aw-model-selector__content" position="popper" sideOffset={6}>
          <Select.Viewport>
            {models.map((model) => (
              <Select.Item className="aw-model-selector__item" key={model.id} value={model.id}>
                <Select.ItemText>
                  <span className="aw-model-selector__item-content">
                    <span className="aw-model-selector__item-label">{model.displayName}</span>
                    <CapabilityIcons model={model} />
                  </span>
                </Select.ItemText>
              </Select.Item>
            ))}
          </Select.Viewport>
        </Select.Content>
      </Select.Portal>
    </Select.Root>
  );
}
