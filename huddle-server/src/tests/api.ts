import { randomBytes, randomInt } from "node:crypto"
import { vi } from "vitest"

type Handler = (request: Request) => Promise<Response>

type CallOptions = {
  ip?: string
  contentType?: string
}

/** A made-up 43-character id, like the ones phones derive. */
export function randomId() {
  return randomBytes(32).toString("base64url")
}

/** A made-up client address, so one test's calls never share a rate-limit window with another's. */
export function randomIp() {
  return `10.${randomInt(256)}.${randomInt(256)}.${randomInt(256)}`
}

/** Made-up ciphertext of exactly `bytes` decoded bytes, as base64url. */
export function sealed(bytes: number) {
  return randomBytes(bytes).toString("base64url")
}

export function apiRequest(body: unknown, { ip = randomIp(), contentType = "application/json" }: CallOptions = {}) {
  return new Request("https://huddle.test/api/v1/test", {
    method: "POST",
    headers: { "Content-Type": contentType, "X-Real-IP": ip },
    body: typeof body === "string" ? body : JSON.stringify(body),
  })
}

/** Posts `body` to a route handler and reads the JSON answer. */
export async function call(handler: Handler, body: unknown, options?: CallOptions) {
  const response = await handler(apiRequest(body, options))
  return { status: response.status, body: await response.json(), headers: response.headers }
}

/** Moves the clock the server code reads (`Date`) forward, leaving timers alone for the database driver. */
export function advanceClock(ms: number) {
  vi.useFakeTimers({ toFake: ["Date"] })
  vi.setSystemTime(Date.now() + ms)
}
