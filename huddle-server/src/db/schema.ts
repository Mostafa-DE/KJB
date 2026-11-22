import { sql } from "drizzle-orm"
import { bigserial, check, index, integer, pgTable, text, timestamp } from "drizzle-orm/pg-core"

export const messageKinds = ["card", "play", "profile"] as const

/**
 * Encrypted messages waiting in a mailbox. `mailbox_hash` is the SHA-256 of the mailbox id (see `storageKey`),
 * never the id itself; `data` is the phone's ciphertext as base64url, and `size` its decoded length in bytes.
 */
export const messages = pgTable(
  "messages",
  {
    id: bigserial("id", { mode: "number" }).primaryKey(),
    mailboxHash: text("mailbox_hash").notNull(),
    kind: text("kind", { enum: messageKinds }).notNull(),
    data: text("data").notNull(),
    size: integer("size").notNull(),
    sentAt: timestamp("sent_at", { withTimezone: true }).notNull(),
    expiresAt: timestamp("expires_at", { withTimezone: true }).notNull(),
  },
  (table) => [
    index("messages_mailbox_idx").on(table.mailboxHash, table.id),
    index("messages_expires_idx").on(table.expiresAt),
    check("messages_kind_check", sql`${table.kind} in ('card', 'play', 'profile')`),
  ],
)

/**
 * One-time invite slots, keyed by the SHA-256 of the invite id (the friend can work it out from the link) and of
 * the owner id (only on the inviting phone). `data` is the friend's sealed answer once accepted.
 */
export const invites = pgTable(
  "invites",
  {
    inviteHash: text("invite_hash").primaryKey(),
    ownerHash: text("owner_hash").notNull().unique(),
    data: text("data"),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull(),
    acceptedAt: timestamp("accepted_at", { withTimezone: true }),
    expiresAt: timestamp("expires_at", { withTimezone: true }).notNull(),
  },
  (table) => [index("invites_expires_idx").on(table.expiresAt)],
)
