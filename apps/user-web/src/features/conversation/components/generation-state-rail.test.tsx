import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { GenerationStateRail } from './generation-state-rail';

const PUBLIC_SUMMARY_MAX_LENGTH = 120;

it.each([
  ['PENDING', '等待生成', 'true'],
  ['STREAMING', '正在生成', 'true'],
  ['SUCCEEDED', '生成完成', 'false'],
  ['STOPPED', '已停止', 'false'],
  ['INTERRUPTED', '生成中断', 'false'],
  ['FAILED', '生成失败', 'false'],
  ['SYNCING', '正在同步', 'true']
] as const)('为 %s 状态提供可访问生命周期播报和 aria-busy', (status, text, busy) => {
  render(<GenerationStateRail status={status} />);

  const container = screen.getByTestId('generation-state-rail-container');
  const rail = screen.getByRole('status');
  expect(container.getAttribute('aria-busy')).toBe(busy);
  expect(rail.getAttribute('aria-live')).toBe('polite');
  expect(rail.getAttribute('aria-atomic')).toBe('true');
  expect(rail.hasAttribute('aria-busy')).toBe(false);
  expect(rail.textContent).toContain(text);
  expect(rail.querySelector('svg')).toBeTruthy();
});

it('不把 content.delta 内容写入 live region', () => {
  render(
    <GenerationStateRail
      status="STREAMING"
      contentDelta="不应被读屏逐段播报"
    />
  );

  const rail = screen.getByRole('status');
  expect(rail.textContent).toContain('正在生成');
  expect(rail.textContent).not.toContain('不应被读屏逐段播报');
});

it('失败状态可显示错误摘要，但仍保留文字和图标语义', () => {
  render(<GenerationStateRail status="FAILED" errorSummary="请求失败（ERR_NETWORK）" />);

  expect(screen.getByRole('status').textContent).toContain('请求失败（ERR_NETWORK）');
  expect(screen.getByRole('status').querySelector('svg')).toBeTruthy();
});

it.each([
  'https://example.com/private?token=hidden',
  'C:\\Users\\name\\secret.txt',
  '/srv/app/secrets.txt',
  'Authorization: Bearer hidden-token',
  'api_key=hidden-value',
  'password: hidden-value',
  'Traceback (most recent call last):\\n  at service.ts:42',
  '\u0000\u0001\u0002'
])('敏感或空错误摘要回退为固定公共文案：%s', (errorSummary) => {
  render(<GenerationStateRail status="FAILED" errorSummary={errorSummary} />);

  expect(screen.getByRole('status').textContent).toBe('生成失败');
});

it('去控制字符并按固定上限截断普通错误摘要', () => {
  const summary = `请求失败${'a'.repeat(PUBLIC_SUMMARY_MAX_LENGTH + 20)}\u0000`;
  render(<GenerationStateRail status="FAILED" errorSummary={summary} />);

  const text = screen.getByRole('status').textContent ?? '';
  expect(text.startsWith('生成失败：请求失败')).toBe(true);
  expect(text.length).toBeLessThanOrEqual('生成失败：'.length + PUBLIC_SUMMARY_MAX_LENGTH);
  expect(text.includes('\u0000')).toBe(false);
});

it('只为 STREAMING 状态渲染独立末端 marker，不移动整条状态轨', () => {
  const { rerender } = render(<GenerationStateRail status="STREAMING" />);

  expect(screen.getByTestId('generation-state-rail-marker')).toBeTruthy();
  expect(screen.getByRole('status').className).toContain('aw-state-rail--streaming');

  rerender(<GenerationStateRail status="SUCCEEDED" />);
  expect(screen.queryByTestId('generation-state-rail-marker')).toBeNull();
});

it('CSS 为 reduced-motion 禁用 marker 动画且不对整条 rail 使用 transform', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8');

  expect(css).toContain('@media (prefers-reduced-motion: reduce)');
  expect(css).toMatch(/\.aw-state-rail--streaming \.aw-state-rail__marker[^{]*\{[\s\S]*animation:\s*none/);
  expect(css).not.toMatch(/\.aw-state-rail--streaming\s*\{[^}]*transform\s*:/);
});
