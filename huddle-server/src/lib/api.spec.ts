import { DrizzleQueryError } from "drizzle-orm"
import { describe, expect, it, vi } from "vitest"
import { ApiError, clientIp, enforceRate, errorResponse } from "@/lib/api"
import { LIMITS } from "@/lib/limits"
import { handleAck } from "@/routes/api/v1/messages/ack"
import { advanceClock, apiRequest, call, randomId, randomIp, sealed } from "@/tests/api"

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

describe("an unexpected error", () => {
  it("is logged by its kind and code only, never with the query's values", async () => {
    const data = sealed(30)
    const cause = Object.assign(new Error(`invalid input "${data}"`), { code: "22P02" })
    const logged = vi.spyOn(console, "error").mockImplementation(() => {})

    const answer = errorResponse(new DrizzleQueryError("insert into messages values ($1)", [data], cause))
    const lines = logged.mock.calls.map((args) => args.join(" ")).join("\n")
    logged.mockRestore()

    expect(answer.status).toBe(500)
    expect(await answer.json()).toEqual({ error: "server_error" })
    expect(lines).toContain("DrizzleQueryError")
    expect(lines).toContain("22P02")
    expect(lines).not.toContain(data)
    expect(lines).not.toContain("insert into")
  })
})

describe("the rate counters", () => {
  const rule = { limit: 2, windowMs: 60 * MINUTE_MS }

  it("keep a busy window when they are full, and forget the least used ones", () => {
    const busy = `test-busy:${randomId()}`
    enforceRate(busy, rule)
    for (let i = 0; i < LIMITS.rateKeys; i++) {
      enforceRate(`test-fill:${i}:${randomId()}`, rule)
      if (i === LIMITS.rateKeys / 2) enforceRate(busy, rule)
    }

    expect(() => enforceRate(busy, rule)).toThrow(ApiError)
  })
})

describe("the address a limit counts", () => {
  function addressOf(ip: string) {
    return clientIp(new Request("https://huddle.test/", { headers: { "X-Real-IP": ip } }))
  }

  it("is the IPv4 address as given", () => {
    expect(addressOf("10.1.2.3")).toBe("10.1.2.3")
    expect(addressOf("10.1.2.3")).not.toBe(addressOf("10.1.2.4"))
  })

  it("is the /64 network of an IPv6 address, however it is written", () => {
    expect(addressOf("2001:db8:1:2:aaaa::1")).toBe(addressOf("2001:0db8:0001:0002:ffff:0:0:9"))
    expect(addressOf("2001:db8:1:2::1")).not.toBe(addressOf("2001:db8:1:3::1"))
    expect(addressOf("::1")).toBe(addressOf("0:0:0:0:1::2"))
  })

  it("keeps an IPv4 address written as IPv6 apart from others", () => {
    expect(addressOf("::ffff:10.1.2.3")).toBe(addressOf("10.1.2.3"))
    expect(addressOf("::ffff:10.1.2.3")).not.toBe(addressOf("::ffff:10.1.2.4"))
  })
})
