const KIB = 1024
const MIB = 1024 * KIB
const MINUTE_MS = 60_000
const HOUR_MS = 60 * MINUTE_MS
const DAY_MS = 24 * HOUR_MS

/** Every size, count, time and rate limit of the Huddle API. API.md documents the same numbers. */
export const LIMITS = {
  bodyBytes: 96 * KIB,
  dataBytes: { card: 64 * KIB, profile: 4 * KIB, play: 2 * KIB, now: 2 * KIB },
  acceptBytes: 4 * KIB,
  keepMs: { card: 30 * DAY_MS, profile: 30 * DAY_MS, play: DAY_MS, now: 10 * MINUTE_MS },
  inviteKeepMs: 7 * DAY_MS,
  mailboxMessages: 200,
  mailboxBytes: 4 * MIB,
  mailboxPlays: 500,
  inboxMailboxes: 64,
  inboxMessagesPerMailbox: 100,
  inboxBytes: MIB,
  // A held inbox read (`wait`) answers at the latest after this, well inside Railway's 5 minutes without data.
  inboxWaitMs: 25_000,
  // Held reads at once, server-wide; past it a read is answered at once, as without `wait`.
  inboxWaiters: 1_000,
  batchEntries: 64,
  deleteIds: 100,
  // Per-address limits leave room for carrier NAT and one home Wi-Fi. 10 friends behind one address, all with KJB in
  // front and all playing, each keep a held inbox read open (answered at each message that lands, about 15 a minute)
  // and send each play, card and now (every change, and a heartbeat a minute) to 9 friends: about 3,000 API calls per
  // 10 minutes, 28 MiB of data in a busy hour and 200 MiB in a heavy day (the nows of 8 hours of music, about 60 MiB,
  // on top of 140). Pages count apart from API calls; /health and assetlinks.json are never limited (src/server.ts).
  // Held reads also count in a window of their own.
  rate: {
    ip: { limit: 6000, windowMs: 10 * MINUTE_MS },
    pages: { limit: 3000, windowMs: 10 * MINUTE_MS },
    inviteCreate: { limit: 60, windowMs: HOUR_MS },
    inviteAccept: { limit: 60, windowMs: HOUR_MS },
    ipBytes: { limit: 32 * MIB, windowMs: HOUR_MS },
    ipBytesDay: { limit: 320 * MIB, windowMs: DAY_MS },
    mailboxSends: { limit: 240, windowMs: HOUR_MS },
    mailboxNows: { limit: 1200, windowMs: HOUR_MS },
    inboxWaits: { limit: 3000, windowMs: 10 * MINUTE_MS },
  },
  rateKeys: 50_000,
  storeMbDefault: 1024,
  storeCheckMs: 5 * MINUTE_MS,
  cleanupEveryMs: DAY_MS,
}
