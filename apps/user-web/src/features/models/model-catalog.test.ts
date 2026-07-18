import type { ModelView } from '@autumn-wind/api-contracts';
import { describe, expect, it, vi } from 'vitest';

import {
  createModelCatalog,
  filterAvailableTextModels,
  getDefaultModelId
} from './model-catalog';
import { createHttpModelCatalog } from './http-model-catalog';
import { createMockModelCatalog } from './mock-model-catalog';

const baseModel = {
  id: '00000000-0000-4000-8000-000000000001',
  ownerUserId: '00000000-0000-4000-8000-000000000002',
  endpointId: '00000000-0000-4000-8000-000000000003',
  providerModelId: 'mock-model',
  displayName: 'Mock Model',
  capabilities: {
    interfaceType: 'CHAT_COMPLETIONS',
    inputModalities: ['TEXT'],
    outputModality: 'TEXT',
    streaming: true,
    systemPrompt: true,
    reasoning: true,
    contextLength: 8192,
    maxOutputLength: 2048
  },
  enabled: true,
  defaultModel: false,
  capabilitySchemaVersion: 1,
  version: 1,
  createdAt: '2026-07-19T12:00:00Z',
  updatedAt: '2026-07-19T12:00:00Z'
} satisfies ModelView;

type ModelOverrides = Omit<Partial<ModelView>, 'capabilities'> & {
  capabilities?: Partial<ModelView['capabilities']>;
};

function model(overrides: ModelOverrides): ModelView {
  return {
    ...baseModel,
    ...overrides,
    capabilities: { ...baseModel.capabilities, ...overrides.capabilities }
  };
}

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' }
  });
}

describe('ModelCatalog', () => {
  it('只保留启用、文本对话、文本输入和文本输出模型', () => {
    const available = model({ id: '00000000-0000-4000-8000-000000000010', displayName: 'Available' });
    const disabled = model({ id: '00000000-0000-4000-8000-000000000011', enabled: false });
    const image = model({
      id: '00000000-0000-4000-8000-000000000012',
      capabilities: { interfaceType: 'IMAGE_GENERATION', outputModality: 'IMAGE' }
    });
    const noTextInput = model({
      id: '00000000-0000-4000-8000-000000000013',
      capabilities: { inputModalities: ['IMAGE'] }
    });

    expect(filterAvailableTextModels([disabled, image, noTextInput, available])).toEqual([available]);
  });

  it('默认模型优先，否则按展示名和 ID 的稳定顺序选择', () => {
    const zed = model({ id: '00000000-0000-4000-8000-000000000014', displayName: 'Zed' });
    const alpha = model({ id: '00000000-0000-4000-8000-000000000015', displayName: 'Alpha' });
    const preferred = model({
      id: '00000000-0000-4000-8000-000000000016',
      displayName: 'Zed Preferred',
      defaultModel: true
    });

    expect(getDefaultModelId([zed, alpha])).toBe(alpha.id);
    expect(getDefaultModelId([zed, preferred, alpha])).toBe(preferred.id);
  });

  it('HTTP 适配器请求固定模型路径并携带 Cookie', async () => {
    let request: Request | undefined;
    const fetchImpl: typeof fetch = vi.fn(async (input, init) => {
      const value = input instanceof URL ? input.href : typeof input === 'string' ? input : input.url;
      request = new Request(new URL(value, 'https://mock.invalid'), init);
      return jsonResponse([baseModel]);
    });

    await expect(createHttpModelCatalog(fetchImpl).listAvailableTextModels()).resolves.toEqual([baseModel]);
    expect(request?.url).toBe('https://mock.invalid/api/v1/model-registry/models');
    expect(request?.credentials).toBe('include');
    expect(request?.headers.get('Accept')).toBe('application/json');
  });

  it('Mock 适配器只提供固定模型能力，不暴露端点详情', async () => {
    const models = await createMockModelCatalog().listAvailableTextModels();

    expect(models.length).toBeGreaterThan(0);
    expect(models[0]).not.toHaveProperty('baseUrl');
    expect(models[0]).not.toHaveProperty('apiKey');
    expect(models.every((item) => item.id.startsWith('00000000-0000-4000-8000-'))).toBe(true);
    expect(models.every((item) => item.capabilities.inputModalities.includes('TEXT'))).toBe(true);
  });

  it('工厂按显式模式创建 HTTP 或 Mock 目录', () => {
    expect(createModelCatalog('mock')).toBeInstanceOf(Object);
    expect(createModelCatalog('http')).toBeInstanceOf(Object);
  });
});
