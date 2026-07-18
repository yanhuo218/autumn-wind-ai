import type { ModelView } from '@autumn-wind/api-contracts';

import { createHttpModelCatalog } from './http-model-catalog';
import { createMockModelCatalog } from './mock-model-catalog';

export interface ModelCatalog {
  listAvailableTextModels(signal?: AbortSignal): Promise<ModelView[]>;
}

export function filterAvailableTextModels(models: ModelView[]): ModelView[] {
  return models
    .filter(
      (model) =>
        model.enabled &&
        model.capabilities.interfaceType === 'CHAT_COMPLETIONS' &&
        model.capabilities.inputModalities.includes('TEXT') &&
        model.capabilities.outputModality === 'TEXT'
    )
    .sort((left, right) => {
      const displayNameOrder = left.displayName.localeCompare(right.displayName, 'zh-Hans');
      return displayNameOrder === 0 ? left.id.localeCompare(right.id) : displayNameOrder;
    });
}

export function getDefaultModelId(models: ModelView[]): string | undefined {
  const available = filterAvailableTextModels(models);
  return available.find((model) => model.defaultModel)?.id ?? available[0]?.id;
}

export function createModelCatalog(mode: 'mock' | 'http'): ModelCatalog {
  return mode === 'mock' ? createMockModelCatalog() : createHttpModelCatalog();
}
