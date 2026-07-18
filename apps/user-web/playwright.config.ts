import { fileURLToPath } from 'node:url';
import { defineConfig, devices } from '@playwright/test';
import { dirname, resolve } from 'node:path';

const configDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(configDirectory, '../..');
const reuseExistingServer = process.env.PLAYWRIGHT_REUSE_EXISTING_SERVER === '1';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? 'dot' : 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    ...devices['Desktop Chrome']
  },
  webServer: [
    {
      command: 'node scripts/mock-conversation-api.mjs',
      cwd: projectRoot,
      url: 'http://127.0.0.1:4174/api/v1/conversations',
      reuseExistingServer,
      gracefulShutdown: { signal: 'SIGTERM', timeout: 1_000 },
      timeout: 120_000
    },
    {
      command: 'node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 4173',
      cwd: resolve(projectRoot, 'apps/user-web'),
      url: 'http://127.0.0.1:4173/chat',
      reuseExistingServer,
      gracefulShutdown: { signal: 'SIGTERM', timeout: 1_000 },
      timeout: 120_000
    }
  ]
});
