import { describe, expect, it } from "vitest"
import { handleHealth } from "@/routes/health"

describe("GET /health", () => {
  it("is ok when the database answers", async () => {
    const answer = await handleHealth(new Request("https://huddle.test/health", { headers: { "X-Real-IP": "10.9.9.9" } }))

    expect(answer.status).toBe(200)
    expect(answer.headers.get("Cache-Control")).toBe("no-store")
    expect(await answer.json()).toEqual({ ok: true })
  })
})
