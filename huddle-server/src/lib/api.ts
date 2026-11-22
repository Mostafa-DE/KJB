import { z } from "zod"
import { LIMITS } from "@/lib/limits"

/** A failure the API answers with its own status and error code (API.md, "Errors"). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly headers: Record<string, string> = {},
  ) {
    super(code)
  }
}

export function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return Response.json(body, { status, headers: { "Cache-Control": "no-store", ...headers } })
}

const BASE64URL = /^[A-Za-z0-9_-]+$/

/** A secret id: 32 bytes as base64url without padding. The server hashes it and never decodes it. */
export const idSchema = z.string().length(43).regex(BASE64URL)

/** Ciphertext as base64url without padding (a length of 1 mod 4 cannot come from whole bytes). */
export const dataSchema = z
  .string()
  .min(2)
  .regex(BASE64URL)
  .refine((data) => data.length % 4 !== 1)

/** A message id as the API writes it: a positive decimal integer that fits a JS number. */
export const messageIdSchema = z.string().regex(/^[1-9][0-9]{0,14}$/)

/** True when no mailbox appears twice in a batch. */
export function eachMailboxOnce(entries: Array<{ mailbox: string }>) {
  return new Set(entries.map((entry) => entry.mailbox)).size === entries.length
}

/** The decoded size in bytes of base64url data without padding. */
export function dataSize(data: string) {
  return Math.floor((data.length * 3) / 4)
}

// In-process, fixed-window counters for the one Railway replica. They reset on a redeploy; a second replica would
// need a shared store.
const rateWindows = new Map<string, { used: number; endsAt: number }>()

function sweepRateWindows(now: number) {
  for (const [key, window] of rateWindows) {
    if (window.endsAt <= now) rateWindows.delete(key)
  }
  // Still too many live keys means a flood of distinct addresses or mailboxes: forget them all rather than grow.
  if (rateWindows.size >= LIMITS.rateKeys) rateWindows.clear()
}

/** Counts `cost` against `key`, or throws `429 rate_limited` with the seconds left in the window. */
export function enforceRate(key: string, rule: { limit: number; windowMs: number }, cost = 1) {
  const now = Date.now()
  if (rateWindows.size >= LIMITS.rateKeys) sweepRateWindows(now)
  const window = rateWindows.get(key)
  const current = window && window.endsAt > now ? window : { used: 0, endsAt: now + rule.windowMs }
  if (current.used + cost > rule.limit) {
    const retryAfter = Math.max(1, Math.ceil((current.endsAt - now) / 1000))
    throw new ApiError(429, "rate_limited", { "Retry-After": String(retryAfter) })
  }
  rateWindows.set(key, { used: current.used + cost, endsAt: current.endsAt })
}

/** The caller's address. Railway's edge sets X-Real-IP to it (docs.railway.com, public networking specs). */
export function clientIp(request: Request) {
  return request.headers.get("x-real-ip")?.trim() || "unknown"
}

async function readJsonBody(request: Request) {
  const mediaType = request.headers.get("content-type")?.split(";")[0].trim().toLowerCase()
  if (mediaType !== "application/json") throw new ApiError(415, "unsupported_media_type")
  if (Number(request.headers.get("content-length") ?? 0) > LIMITS.bodyBytes) throw new ApiError(413, "too_large")

  const chunks: Uint8Array[] = []
  let received = 0
  if (request.body) {
    // Read with a cap, so a body sent without a length (or with a false one) can never fill memory.
    const reader = request.body.getReader()
    for (let chunk = await reader.read(); !chunk.done; chunk = await reader.read()) {
      received += chunk.value.byteLength
      if (received > LIMITS.bodyBytes) {
        await reader.cancel()
        throw new ApiError(413, "too_large")
      }
      chunks.push(chunk.value)
    }
  }
  try {
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks))) as unknown
  } catch {
    throw new ApiError(400, "bad_request")
  }
}

/** The answer for anything thrown while handling a request. Unexpected errors are logged without request data. */
export function errorResponse(error: unknown) {
  if (error instanceof ApiError) return json(error.status, { error: error.code }, error.headers)
  console.error("huddle: unexpected error:", error instanceof Error ? error.message : String(error))
  return json(500, { error: "server_error" })
}

/**
 * The shape of every `/api/v1` handler: the per-address limit, a capped JSON body checked by `schema`, then
 * `handle`. Errors become the API's JSON errors; a bad field is named by its path.
 */
export function apiRoute<Schema extends z.ZodType>(
  schema: Schema,
  handle: (input: z.output<Schema>, ip: string) => Promise<Response>,
) {
  return async (request: Request) => {
    try {
      const ip = clientIp(request)
      enforceRate(`ip:${ip}`, LIMITS.rate.ip)
      const parsed = schema.safeParse(await readJsonBody(request))
      if (!parsed.success) {
        const field = parsed.error.issues[0]?.path.join(".")
        return json(400, { error: "bad_request", field: field || undefined })
      }
      return await handle(parsed.data, ip)
    } catch (error) {
      return errorResponse(error)
    }
  }
}
