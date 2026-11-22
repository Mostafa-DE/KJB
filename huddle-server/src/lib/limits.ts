const KIB = 1024
const MIB = 1024 * KIB
const MINUTE_MS = 60_000
const HOUR_MS = 60 * MINUTE_MS
const DAY_MS = 24 * HOUR_MS

/** Every size, count, time and rate limit of the Huddle API. API.md documents the same numbers. */
export const LIMITS = {
  bodyBytes: 96 * KIB,
  dataBytes: { card: 64 * KIB, profile: 4 * KIB, play: 2 * KIB },
  acceptBytes: 4 * KIB,
  keepMs: { card: 30 * DAY_MS, profile: 30 * DAY_MS, play: DAY_MS },
  inviteKeepMs: 7 * DAY_MS,
  mailboxMessages: 200,
  mailboxBytes: 4 * MIB,
  mailboxPlays: 500,
  inboxMailboxes: 64,
  inboxMessagesPerMailbox: 100,
  inboxBytes: MIB,
  batchEntries: 64,
  deleteIds: 100,
  rate: {
    ip: { limit: 600, windowMs: 10 * MINUTE_MS },
    inviteCreate: { limit: 30, windowMs: HOUR_MS },
    inviteAccept: { limit: 30, windowMs: HOUR_MS },
    ipBytes: { limit: 16 * MIB, windowMs: HOUR_MS },
    // Cards wait up to 30 days: 30 days of this, as base64, stays under the storage guard's default 1 GiB, so one
    // address alone can never fill it.
    ipBytesDay: { limit: 24 * MIB, windowMs: DAY_MS },
    mailboxSends: { limit: 240, windowMs: HOUR_MS },
  },
  rateKeys: 50_000,
  storeMbDefault: 1024,
  storeCheckMs: 5 * MINUTE_MS,
  cleanupEveryMs: DAY_MS,
}
