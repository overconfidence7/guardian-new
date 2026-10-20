import { defineConfig, devices } from '@playwright/test';

/*
 * Two projects:
 *  - screenshots: the fixture-backed /demo, no backend needed. Writes PNGs to docs-screenshots/.
 *  - emulator: end-to-end against the Firebase Emulator Suite (project demo-guardian).
 *    Start it first: (cd ../firebase && firebase emulators:start --project demo-guardian)
 *    or run everything with: firebase emulators:exec --project demo-guardian "cd ../web && npm run e2e"
 */
const PORT = Number(process.env.E2E_PORT ?? 5173);

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  // The emulator specs share one Firebase emulator and one clears it in beforeAll.
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  timeout: 60_000,
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    trace: 'retain-on-failure',
    locale: 'en-US',
    timezoneId: 'America/Chicago',
  },
  projects: [
    {
      name: 'screenshots',
      testMatch: /screenshots\.spec\.ts/,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'emulator',
      testMatch: /\.e2e\.ts$/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 900 } },
    },
  ],
  webServer: {
    command: `npx vite --port ${PORT} --strictPort --host 127.0.0.1`,
    url: `http://127.0.0.1:${PORT}`,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: { VITE_USE_EMULATORS: 'true' },
  },
});
