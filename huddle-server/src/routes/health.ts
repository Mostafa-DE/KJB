import { createFileRoute } from "@tanstack/react-router"
import { sql } from "drizzle-orm"
import { db } from "@/db"
import { ApiError, clientIp, enforceRate, errorResponse, json } from "@/lib/api"
import { LIMITS } from "@/lib/limits"

export async function handleHealth(request: Request) {
  try {
    enforceRate(`ip:${clientIp(request)}`, LIMITS.rate.ip)
    await db.execute(sql`select 1`)
    return json(200, { ok: true })
  } catch (error) {
    if (error instanceof ApiError) return errorResponse(error)
    console.error("huddle: health check found no database:", error instanceof Error ? error.message : String(error))
    return json(503, { ok: false })
  }
}

export const Route = createFileRoute("/health")({
  server: {
    handlers: {
      GET: ({ request }) => handleHealth(request),
    },
  },
})
