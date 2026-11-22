import { describe, expect, it } from "vitest"
import { LIMITS } from "@/lib/limits"
import { handleAck } from "@/routes/api/v1/messages/ack"
import { advanceClock, apiRequest, call, randomId, randomIp } from "@/tests/api"

const MINUTE_MS = 60 * 1000

function ackBody() {
  return { acks: [{ mailbox: randomId(), upTo: "1" }] }
}

describe("every /api/v1 call", () => {
  it("answers JSON that is never cached", async () => {
    const answer = await call(handleAck, ackBody())

    expect(answer.status).toBe(200)
    expect(answer.headers.get("Content-Type")).toContain("application/json")
    expect(answer.headers.get("Cache-Control")).toBe("no-store")
  })

  it("refuses a body that is not JSON content", async () => {
    const answer = await call(handleAck, ackBody(), { contentType: "text/plain" })

    expect(answer).toMatchObject({ status: 415, body: { error: "unsupported_media_type" } })
  })

  it("accepts JSON with a charset", async () => {
    const answer = await call(handleAck, ackBody(), { contentType: "application/json; charset=utf-8" })

    expect(answer.status).toBe(200)
  })

  it("refuses broken JSON", async () => {
    const answer = await call(handleAck, "{\"acks\": [")

    expect(answer).toMatchObject({ status: 400, body: { error: "bad_request" } })
  })

  it("names the first bad field", async () => {
    const answer = await call(handleAck, { acks: [{ upTo: "1" }] })

    expect(answer).toMatchObject({ status: 400, body: { error: "bad_request", field: "acks.0.mailbox" } })
  })

  it("refuses a body over 96 KiB, with or without a length header", async () => {
    const big = JSON.stringify({ acks: [], padding: "x".repeat(LIMITS.bodyBytes) })
    const withLength = await handleAck(apiRequest(big))
    const streamed = await handleAck(
      new Request("https://huddle.test/api/v1/messages/ack", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Real-IP": randomIp() },
        body: new Blob([big]).stream(),
        duplex: "half",
      } as RequestInit),
    )

    expect(withLength.status).toBe(413)
    expect(await withLength.json()).toEqual({ error: "too_large" })
    expect(streamed.status).toBe(413)
  })

  it("allows 600 calls per 10 minutes from one address, then again after the window", async () => {
    const ip = randomIp()
    for (let i = 0; i < LIMITS.rate.ip.limit; i++) {
      const answer = await call(handleAck, ackBody(), { ip })
      expect(answer.status).toBe(200)
    }

    const refused = await call(handleAck, ackBody(), { ip })
    advanceClock(10 * MINUTE_MS)
    const later = await call(handleAck, ackBody(), { ip })

    expect(refused).toMatchObject({ status: 429, body: { error: "rate_limited" } })
    expect(Number(refused.headers.get("Retry-After"))).toBeGreaterThan(0)
    expect(Number(refused.headers.get("Retry-After"))).toBeLessThanOrEqual(600)
    expect(later.status).toBe(200)
  })
})
