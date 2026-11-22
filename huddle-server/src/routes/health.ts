import { createFileRoute } from "@tanstack/react-router"
import { sql } from "drizzle-orm"
import { db } from "@/db"
import { describeError, json } from "@/lib/api"

// Never rate limited (src/server.ts): Railway's health check calls it.
export async function handleHealth() {
  try {
    await db.execute(sql`select 1`)
    return json(200, { ok: true })
  } catch (error) {
    console.error("huddle: health check found no database:", describeError(error))
    return json(503, { ok: false })
  }
}

export const Route = createFileRoute("/health")({
  server: {
    handlers: {
      GET: () => handleHealth(),
    },
  },
})
