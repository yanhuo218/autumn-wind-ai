import type { ModelView } from '@autumn-wind/api-contracts';

import { filterAvailableTextModels, type ModelCatalog } from './model-catalog';

const mockModels: ModelView[] = [
  {
    id: '00000000-0000-4000-8000-000000000101',
    ownerUserId: '00000000-0000-4000-8000-000000000102',
    endpointId: '00000000-0000-4000-8000-000000000103',
    providerModelId: 'mock-text-standard',
    displayName: '秋风文本标准',
    capabilities: {
      interfaceType: 'CHAT_COMPLETIONS',
      inputModalities: ['TEXT'],
      outputModality: 'TEXT',
      streaming: true,
      systemPrompt: true,
      reasoning: false,
      contextLength: 8192,
      maxOutputLength: 2048
    },
    enabled: true,
    defaultModel: true,
    capabilitySchemaVersion: 1,
    version: 1,
    createdAt: '2026-07-19T12:00:00Z',
    updatedAt: '2026-07-19T12:00:00Z'
  },
  {
    id: '00000000-0000-4000-8000-000000000104',
    ownerUserId: '00000000-0000-4000-8000-000000000105',
    endpointId: '00000000-0000-4000-8000-000000000106',
    providerModelId: 'mock-text-reasoning',
    displayName: '秋风文本推理',
    capabilities: {
      interfaceType: 'CHAT_COMPLETIONS',
      inputModalities: ['TEXT', 'IMAGE', 'FILE'],
      outputModality: 'TEXT',
      streaming: true,
      systemPrompt: true,
      reasoning: true,
      contextLength: 16384,
      maxOutputLength: 4096
    },
    enabled: true,
    defaultModel: false,
    capabilitySchemaVersion: 1,
    version: 1,
    createdAt: '2026-07-19T12:00:00Z',
    updatedAt: '2026-07-19T12:00:00Z'
  }
];

export function createMockModelCatalog(): ModelCatalog {
  return {
    async listAvailableTextModels() {
      return filterAvailableTextModels(mockModels);
    }
  };
}
