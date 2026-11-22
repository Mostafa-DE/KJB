import { createHash } from "node:crypto"
import { drizzle } from "drizzle-orm/node-postgres"
import { Pool } from "pg"
import * as schema from "@/db/schema"

const connectionString = process.env.DATABASE_URL
if (!connectionString) {
  throw new Error("DATABASE_URL is not set")
}

const pool = new Pool({ connectionString })
// An idle client that drops (a database restart) must not crash the server; the pool reconnects on next use.
pool.on("error", (error) => console.error("huddle: database pool error:", error.message))

export const db = drizzle(pool, { schema })

/** What the database stores in place of a secret id: its SHA-256 (hex), so a leaked database gives no access. */
export function storageKey(id: string) {
  return createHash("sha256").update(id).digest("hex")
}
