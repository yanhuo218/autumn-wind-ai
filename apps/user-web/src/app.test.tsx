import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';

import { App } from './app';

it('渲染可访问的用户端根区域', () => {
  render(<App />);

  const root = screen.getByRole('main');
  expect(root.dataset.testid).toBe('user-web-root');
  expect(screen.getByTestId('user-web-root')).toBe(root);
});
