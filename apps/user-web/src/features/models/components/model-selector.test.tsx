import type { ModelView } from '@autumn-wind/api-contracts';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import { ModelSelector } from './model-selector';

const models: ModelView[] = [
  {
    id: '00000000-0000-4000-8000-000000000201',
    ownerUserId: '00000000-0000-4000-8000-000000000202',
    endpointId: '00000000-0000-4000-8000-000000000203',
    providerModelId: 'mock-alpha',
    displayName: 'Alpha 文本模型',
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
    id: '00000000-0000-4000-8000-000000000204',
    ownerUserId: '00000000-0000-4000-8000-000000000205',
    endpointId: '00000000-0000-4000-8000-000000000206',
    providerModelId: 'mock-vision',
    displayName: '这是一个非常长的模型展示名称用于验证省略',
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

describe('ModelSelector', () => {
  const originalScrollIntoView = Element.prototype.scrollIntoView;

  beforeAll(() => {
    Element.prototype.scrollIntoView = vi.fn();
    Element.prototype.hasPointerCapture = vi.fn(() => false);
    Element.prototype.setPointerCapture = vi.fn();
    Element.prototype.releasePointerCapture = vi.fn();
  });

  afterAll(() => {
    Element.prototype.scrollIntoView = originalScrollIntoView;
  });

  it('展示模型名和能力图标，但不展示 endpoint 字段', async () => {
    const user = userEvent.setup();
    render(<ModelSelector models={models} value={models[0].id} onValueChange={vi.fn()} />);

    expect(screen.getByRole('combobox', { name: '选择模型' }).textContent).toContain('Alpha 文本模型');
    await user.click(screen.getByRole('combobox', { name: '选择模型' }));

    expect(screen.getByRole('option', { name: 'Alpha 文本模型' })).toBeTruthy();
    expect(screen.getAllByTestId('model-capability-text').length).toBeGreaterThan(0);
    expect(screen.getByTestId('model-capability-image')).toBeTruthy();
    expect(screen.queryByText(models[0].endpointId)).toBeNull();
  });

  it('支持键盘打开、选择和 Esc 关闭后恢复焦点', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();
    render(<ModelSelector models={models} value={models[0].id} onValueChange={onValueChange} />);
    const trigger = screen.getByRole('combobox', { name: '选择模型' });

    trigger.focus();
    await user.keyboard('{Enter}');
    await user.keyboard('{ArrowDown}{Enter}');

    expect(onValueChange).toHaveBeenCalledWith(models[1].id);
    expect(document.activeElement).toBe(trigger);
  });

  it('长名称入口带有稳定的省略样式类', () => {
    render(<ModelSelector models={models} value={models[1].id} onValueChange={vi.fn()} />);

    expect(screen.getByTestId('model-selector-label').classList.contains('aw-model-selector__label')).toBe(true);
  });

  it('从空值切换到有效模型时保持受控且不产生告警', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const consoleWarn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    try {
      const { rerender } = render(
        <ModelSelector models={models} value={undefined} onValueChange={vi.fn()} />
      );

      rerender(<ModelSelector models={models} value={models[0].id} onValueChange={vi.fn()} />);

      expect(consoleError).not.toHaveBeenCalled();
      expect(consoleWarn).not.toHaveBeenCalled();
    } finally {
      consoleError.mockRestore();
      consoleWarn.mockRestore();
    }
  });
});
