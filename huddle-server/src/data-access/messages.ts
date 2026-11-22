import { and, asc, count, desc, eq, gt, inArray, lte, ne, or, sql } from "drizzle-orm"
import { db, storageKey } from "@/db"
import { messages, type messageKinds } from "@/db/schema"
import { LIMITS } from "@/lib/limits"

type MessageKind = (typeof messageKinds)[number]

/**
 * Puts one message in a mailbox. Cards and profiles count against the mailbox's room (a new profile replaces the
 * unread one); plays never fill it, and only the newest 500 plays are kept.
 */
export async function sendMessage(mailbox: string, kind: MessageKind, data: string, size: number) {
  const now = new Date()
  const mailboxHash = storageKey(mailbox)
  const expiresAt = new Date(now.getTime() + LIMITS.keepMs[kind])

  return db.transaction(async (tx) => {
    if (kind !== "play") {
      const counted = kind === "profile" ? eq(messages.kind, "card") : ne(messages.kind, "play")
      const [held] = await tx
        .select({ messages: count(), bytes: sql<number>`coalesce(sum(${messages.size}), 0)::int` })
        .from(messages)
        .where(and(eq(messages.mailboxHash, mailboxHash), counted, gt(messages.expiresAt, now)))
      if (held.messages >= LIMITS.mailboxMessages || held.bytes + size > LIMITS.mailboxBytes) {
        return { outcome: "mailbox_full" } as const
      }
    }
    if (kind === "profile") {
      await tx.delete(messages).where(and(eq(messages.mailboxHash, mailboxHash), eq(messages.kind, "profile")))
    }

    const [sent] = await tx
      .insert(messages)
      .values({ mailboxHash, kind, data, size, sentAt: now, expiresAt })
      .returning({ id: messages.id })

    if (kind === "play") {
      const beyondNewest = tx
        .select({ id: messages.id })
        .from(messages)
        .where(and(eq(messages.mailboxHash, mailboxHash), eq(messages.kind, "play")))
        .orderBy(desc(messages.id))
        .offset(LIMITS.mailboxPlays)
      await tx.delete(messages).where(inArray(messages.id, beyondNewest))
    }
    return { outcome: "sent", id: sent.id, sentAt: now, expiresAt } as const
  })
}

/**
 * Live messages of each asked mailbox after its cursor, oldest first, within the per-mailbox and per-answer limits.
 * Plays are deleted as they are handed out; cards and profiles wait for an ack.
 */
export async function readInbox(asked: Array<{ mailbox: string; after?: string }>, now: Date) {
  let budget = LIMITS.inboxBytes
  const handedPlays: number[] = []
  const answers = []

  for (const { mailbox, after } of asked) {
    if (budget <= 0) {
      answers.push({ mailbox, messages: [], more: true })
      continue
    }
    const rows = await db
      .select({ id: messages.id, kind: messages.kind, data: messages.data, size: messages.size, sentAt: messages.sentAt })
      .from(messages)
      .where(
        and(
          eq(messages.mailboxHash, storageKey(mailbox)),
          gt(messages.expiresAt, now),
          after ? gt(messages.id, Number(after)) : undefined,
        ),
      )
      .orderBy(asc(messages.id))
      .limit(LIMITS.inboxMessagesPerMailbox + 1)

    let more = rows.length > LIMITS.inboxMessagesPerMailbox
    const handed = []
    for (const row of rows.slice(0, LIMITS.inboxMessagesPerMailbox)) {
      if (row.size > budget) {
        budget = 0
        more = true
        break
      }
      budget -= row.size
      handed.push({ id: String(row.id), kind: row.kind, data: row.data, sentAt: row.sentAt.getTime() })
      if (row.kind === "play") handedPlays.push(row.id)
    }
    answers.push({ mailbox, messages: handed, more })
  }

  if (handedPlays.length > 0) await db.delete(messages).where(inArray(messages.id, handedPlays))
  return answers
}

/** Deletes, per mailbox, every message up to and including `upTo`; answers how many went. */
export async function ackMessages(acks: Array<{ mailbox: string; upTo: string }>) {
  const deleted = await db
    .delete(messages)
    .where(
      or(...acks.map(({ mailbox, upTo }) => and(eq(messages.mailboxHash, storageKey(mailbox)), lte(messages.id, Number(upTo))))),
    )
  return deleted.rowCount ?? 0
}

/** Deletes the given messages, but only from this mailbox; answers how many went. */
export async function deleteMessages(mailbox: string, ids: string[]) {
  const deleted = await db
    .delete(messages)
    .where(and(eq(messages.mailboxHash, storageKey(mailbox)), inArray(messages.id, ids.map(Number))))
  return deleted.rowCount ?? 0
}

/** Empties these mailboxes; answers how many messages went. */
export async function deleteMailboxes(mailboxes: string[]) {
  const deleted = await db.delete(messages).where(inArray(messages.mailboxHash, mailboxes.map(storageKey)))
  return deleted.rowCount ?? 0
}
