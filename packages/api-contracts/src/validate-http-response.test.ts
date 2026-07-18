import { describe, expect, it } from 'vitest';

import {
  isConversationDetailView,
  isConversationListView,
  isConversationView,
  isGenerationAcceptedView,
  isGenerationView,
  isModelViewList,
  isPublicErrorResponse
} from './validate-http-response';

const validConversationDetail = {
  conversationId: '00000000-0000-4000-8000-000000000001',
  title: '新会话',
  createdAt: '2026-07-19T12:00:00Z',
  archived: false,
  generations: [],
  messages: []
};

const validModel = {
  id: '00000000-0000-4000-8000-000000000001',
  ownerUserId: '00000000-0000-4000-8000-000000000002',
  endpointId: '00000000-0000-4000-8000-000000000003',
  providerModelId: 'model-1',
  displayName: 'Model 1',
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
};

describe('HTTP 响应校验器', () => {
  it('接受合法的会话列表', () => {
    expect(isConversationListView({ items: [] })).toBe(true);
  });

  it('接受合法的会话详情', () => {
    expect(isConversationDetailView(validConversationDetail)).toBe(true);
  });

  it('接受合法的会话摘要', () => {
    expect(
      isConversationView({
        conversationId: '00000000-0000-4000-8000-000000000001',
        title: '新会话',
        createdAt: '2026-07-19T12:00:00Z',
        archived: false
      })
    ).toBe(true);
  });

  it('接受合法的生成受理响应', () => {
    expect(
      isGenerationAcceptedView({
        userMessageId: '00000000-0000-4000-8000-000000000001',
        generationId: '00000000-0000-4000-8000-000000000002',
        statusUrl: '/api/v1/generations/00000000-0000-4000-8000-000000000002',
        eventsUrl: '/api/v1/generations/00000000-0000-4000-8000-000000000002/events'
      })
    ).toBe(true);
  });

  it('接受合法的生成快照', () => {
    expect(
      isGenerationView({
        generationId: '00000000-0000-4000-8000-000000000001',
        conversationId: '00000000-0000-4000-8000-000000000002',
        status: 'PENDING',
        content: {
          schemaVersion: 1,
          blocks: [{ type: 'text', text: '你好' }]
        },
        createdAt: '2026-07-19T12:00:00Z',
        updatedAt: '2026-07-19T12:00:00Z'
      })
    ).toBe(true);
  });

  it('接受合法的模型数组', () => {
    expect(isModelViewList([validModel])).toBe(true);
  });

  it('拒绝缺少必填字段的会话详情', () => {
    const { conversationId: _conversationId, ...missingConversationId } = validConversationDetail;

    expect(isConversationDetailView(missingConversationId)).toBe(false);
  });

  it('拒绝字段类型错误的模型数组', () => {
    expect(isModelViewList([{ ...validModel, version: '1' }])).toBe(false);
  });

  it('接受 Conversation 与 Model Registry 的公共错误响应', () => {
    expect(
      isPublicErrorResponse({
        code: 'AW-CONVERSATION-NOT_FOUND-0001',
        message: '会话不存在',
        correlationId: '00000000-0000-4000-8000-000000000004'
      })
    ).toBe(true);
    expect(
      isPublicErrorResponse({
        code: 'AW-MODEL_REGISTRY-NOT_FOUND-0001',
        message: '模型不存在',
        correlationId: 'correlation-00000001'
      })
    ).toBe(true);
  });
});
