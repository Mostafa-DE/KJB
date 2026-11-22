import { and, eq, gt, isNull } from "drizzle-orm"
import { db, storageKey } from "@/db"
import { invites } from "@/db/schema"
import { LIMITS } from "@/lib/limits"

/** A new waiting invite, or null when its invite or owner id is already in use. */
export async function createInvite(invite: string, owner: string) {
  const now = new Date()
  const [created] = await db
    .insert(invites)
    .values({
      inviteHash: storageKey(invite),
      ownerHash: storageKey(owner),
      createdAt: now,
      expiresAt: new Date(now.getTime() + LIMITS.inviteKeepMs),
    })
    .onConflictDoNothing()
    .returning({ expiresAt: invites.expiresAt })
  return created ?? null
}

/**
 * Stores the friend's answer on a live invite, once; the invite then lives 7 more days for its owner. The same answer
 * again is a retry whose reply was lost, and gets the first accept's time.
 */
export async function acceptInvite(invite: string, data: string) {
  const now = new Date()
  const inviteHash = storageKey(invite)
  const live = and(eq(invites.inviteHash, inviteHash), gt(invites.expiresAt, now))
  const [accepted] = await db
    .update(invites)
    .set({ data, acceptedAt: now, expiresAt: new Date(now.getTime() + LIMITS.inviteKeepMs) })
    .where(and(live, isNull(invites.acceptedAt)))
    .returning({ acceptedAt: invites.acceptedAt })
  if (accepted) return { outcome: "accepted", acceptedAt: now } as const

  const [taken] = await db.select({ data: invites.data, acceptedAt: invites.acceptedAt }).from(invites).where(live)
  if (!taken) return { outcome: "not_found" } as const
  // Compared as hashes, so the time the comparison takes tells nothing about the stored answer.
  if (taken.acceptedAt && taken.data !== null && storageKey(taken.data) === storageKey(data)) {
    return { outcome: "accepted", acceptedAt: taken.acceptedAt } as const
  }
  return { outcome: "already_accepted" } as const
}

/** The live invite this owner id created, or undefined. */
export async function findInvite(owner: string) {
  const [found] = await db
    .select({ data: invites.data, acceptedAt: invites.acceptedAt, expiresAt: invites.expiresAt })
    .from(invites)
    .where(and(eq(invites.ownerHash, storageKey(owner)), gt(invites.expiresAt, new Date())))
  return found
}

/** Deletes the invite this owner id created; answers how many rows went (0 or 1). */
export async function deleteInvite(owner: string) {
  const deleted = await db.delete(invites).where(eq(invites.ownerHash, storageKey(owner)))
  return deleted.rowCount ?? 0
}
