import { defineConfig } from "vitest/config"

// Runs the built server (`pnpm build` first) under srvx, as Railway starts it, against the test database.
export default defineConfig({
  test: {
    environment: "node",
    include: ["e2e/**/*.spec.ts"],
    globalSetup: "./src/tests/global-setup.ts",
    // The rate limit test makes thousands of calls.
    testTimeout: 60_000,
  },
})
