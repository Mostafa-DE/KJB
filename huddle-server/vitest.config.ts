import { defineConfig } from "vitest/config"
import viteReact from "@vitejs/plugin-react"

export default defineConfig({
  resolve: {
    tsconfigPaths: true,
  },
  plugins: [viteReact()],
  test: {
    environment: "node",
    include: ["src/**/*.spec.ts", "src/**/*.spec.tsx"],
    globalSetup: "./src/tests/global-setup.ts",
    setupFiles: ["./src/tests/setup.ts"],
    // One in-memory database for all files: run them one after another so a file's cleanup never meets another's rows.
    fileParallelism: false,
    // Some tests send hundreds of messages one by one; on a busy machine that takes longer than the default 5 s.
    testTimeout: 30_000,
  },
})
