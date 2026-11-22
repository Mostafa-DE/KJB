import { lte, sql } from "drizzle-orm"
import { db } from "@/db"
import { invites, messages } from "@/db/schema"
import { LIMITS } from "@/lib/limits"

/** Deletes every message and invite whose time is up (reads already skip them); answers how many went. */
export async function deleteExpiredRows(now: Date) {
  const deletedMessages = await db.delete(messages).where(lte(messages.expiresAt, now))
  const deletedInvites = await db.delete(invites).where(lte(invites.expiresAt, now))
  return { messages: deletedMessages.rowCount ?? 0, invites: deletedInvites.rowCount ?? 0 }
}

let lastStoreCheck = { at: Number.NEGATIVE_INFINITY, full: false }

/**
 * True when the stored data has reached `HUDDLE_MAX_STORE_MB` (1 GiB by default), so writes stop before the
 * database volume fills. Measured at most every 5 minutes, on live data rather than file sizes, which Postgres
 * does not shrink after deletes.
 */
export async function storeIsFull() {
  const now = Date.now()
  // A clock that went back (or a test that moved it) also forces a fresh measure.
  if (now >= lastStoreCheck.at && now - lastStoreCheck.at < LIMITS.storeCheckMs) return lastStoreCheck.full

  const configured = Number(process.env.HUDDLE_MAX_STORE_MB || LIMITS.storeMbDefault)
  const maxBytes = (Number.isFinite(configured) && configured >= 0 ? configured : LIMITS.storeMbDefault) * 1024 * 1024
  const held = await db.execute<{ bytes: string }>(sql`
    select (select coalesce(sum(length(${messages.data})), 0) from ${messages})
         + (select coalesce(sum(length(${invites.data})), 0) from ${invites}) as bytes
  `)
  lastStoreCheck = { at: now, full: Number(held.rows[0]?.bytes ?? 0) >= maxBytes }
  return lastStoreCheck.full
}
