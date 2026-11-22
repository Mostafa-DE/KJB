import handler, { createServerEntry } from "@tanstack/react-start/server-entry"
import { deleteExpiredRows } from "@/data-access/store"
import { clientIp, describeError, enforceRate, errorResponse, json } from "@/lib/api"
import { LIMITS } from "@/lib/limits"

// The pages run TanStack's own inline hydration scripts, hence 'unsafe-inline'; nothing else may load or frame them.
const SECURITY_HEADERS = {
  "Content-Security-Policy":
    "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
    "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  "Referrer-Policy": "no-referrer",
  "Strict-Transport-Security": "max-age=31536000",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
}

async function cleanUp() {
  try {
    const deleted = await deleteExpiredRows(new Date())
    console.info(`huddle: cleanup removed ${deleted.messages} messages and ${deleted.invites} invites`)
  } catch (error) {
    console.error("huddle: cleanup failed:", describeError(error))
  }
}

// Expired rows are never served; this only frees their space: once at start, then daily.
void cleanUp()
setInterval(cleanUp, LIMITS.cleanupEveryMs).unref()

export default createServerEntry({
  async fetch(request, opts) {
    const api = new URL(request.url).pathname.startsWith("/api/")
    let response: Response
    try {
      // Every request counts once against its address: API calls, pages and /health alike (srvx serves /assets first).
      enforceRate(`ip:${clientIp(request)}`, LIMITS.rate.ip)
      // Every API call is a POST (API.md): any other method, a browser's GET or a CORS preflight, gets a JSON refusal.
      response =
        api && request.method !== "POST"
          ? json(405, { error: "method_not_allowed" }, { Allow: "POST" })
          : await handler.fetch(request, opts)
    } catch (error) {
      response = errorResponse(error)
    }
    const headers = new Headers(response.headers)
    for (const [name, value] of Object.entries(SECURITY_HEADERS)) headers.set(name, value)
    return new Response(response.body, { status: response.status, statusText: response.statusText, headers })
  },
})
