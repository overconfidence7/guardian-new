import { defineConfig } from "vitest/config";

// Runs inside `firebase emulators:exec`, which sets the *_EMULATOR_HOST variables.
// Files share one emulator suite, so they run one at a time.
export default defineConfig({
  test: {
    include: ["test/emulator/**/*.test.ts"],
    environment: "node",
    fileParallelism: false,
    testTimeout: 30_000,
    hookTimeout: 60_000,
  },
});
