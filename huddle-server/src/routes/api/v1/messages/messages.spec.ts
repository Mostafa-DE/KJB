import { describe, expect, it } from "vitest"
import { db } from "@/db"
import { messages } from "@/db/schema"
import { LIMITS } from "@/lib/limits"
import { advanceClock, call, randomId, randomIp, sealed } from "@/tests/api"
import { handleDeleteMailboxes } from "../mailboxes/delete"
import { handleAck } from "./ack"
import { handleDeleteMessages } from "./delete"
import { handleInbox } from "./inbox"
import { handleSendMessage } from "./send"

const HOUR_MS = 60 * 60 * 1000
const DAY_MS = 24 * HOUR_MS

type Kind = "card" | "play" | "profile"

async function send(mailbox: string, kind: Kind, data = sealed(200), ip?: string) {
  const sent = await call(handleSendMessage, { mailbox, kind, data }, { ip })
  expect(sent.status).toBe(201)
  return { id: sent.body.id as string, data }
}

async function inbox(...mailboxes: Array<string | { mailbox: string; after?: string }>) {
  const asked = mailboxes.map((entry) => (typeof entry === "string" ? { mailbox: entry } : entry))
  const answer = await call(handleInbox, { mailboxes: asked })
  expect(answer.status).toBe(200)
  return answer.body as {
    now: number
    mailboxes: Array<{
      mailbox: string
      messages: Array<{ id: string; kind: Kind; data: string; sentAt: number }>
      more: boolean
    }>
  }
}

describe("POST /api/v1/messages/send", () => {
  it("stores a card for 30 days and gives back its id", async () => {
    const before = Date.now()
    const sent = await call(handleSendMessage, { mailbox: randomId(), kind: "card", data: sealed(500) })

    expect(sent.status).toBe(201)
    expect(sent.body.id).toMatch(/^[1-9][0-9]*$/)
    expect(sent.body.sentAt).toBeGreaterThanOrEqual(before)
    expect(sent.body.expiresAt).toBe(sent.body.sentAt + 30 * DAY_MS)
  })

  it("keeps a play for 24 hours", async () => {
    const sent = await call(handleSendMessage, { mailbox: randomId(), kind: "play", data: sealed(100) })

    expect(sent.body.expiresAt).toBe(sent.body.sentAt + DAY_MS)
  })

  it.each([
    ["card", LIMITS.dataBytes.card],
    ["profile", LIMITS.dataBytes.profile],
    ["play", LIMITS.dataBytes.play],
  ] as const)("takes a %s up to its size limit and refuses one byte more", async (kind, limit) => {
    const fits = await call(handleSendMessage, { mailbox: randomId(), kind, data: sealed(limit) })
    const tooBig = await call(handleSendMessage, { mailbox: randomId(), kind, data: sealed(limit + 1) })

    expect(fits.status).toBe(201)
    expect(tooBig).toMatchObject({ status: 413, body: { error: "too_large" } })
  })

  it("refuses an unknown kind and data that is not base64url", async () => {
    const badKind = await call(handleSendMessage, { mailbox: randomId(), kind: "video", data: sealed(10) })
    const badData = await call(handleSendMessage, { mailbox: randomId(), kind: "card", data: "abc+/=" })

    expect(badKind).toMatchObject({ status: 400, body: { error: "bad_request", field: "kind" } })
    expect(badData).toMatchObject({ status: 400, body: { error: "bad_request", field: "data" } })
  })

  it("stores only the hash of the mailbox id", async () => {
    const mailbox = randomId()
    await send(mailbox, "card")

    const rows = await db.select().from(messages)

    expect(rows).toHaveLength(1)
    expect(JSON.stringify(rows)).not.toContain(mailbox)
  })

  it("refuses a card when the mailbox already holds 200 cards and profiles", async () => {
    const mailbox = randomId()
    for (let i = 0; i < LIMITS.mailboxMessages - 1; i++) await send(mailbox, "card", sealed(20))
    await send(mailbox, "profile", sealed(20))

    const full = await call(handleSendMessage, { mailbox, kind: "card", data: sealed(20) })
    const play = await call(handleSendMessage, { mailbox, kind: "play", data: sealed(20) })

    expect(full).toMatchObject({ status: 409, body: { error: "mailbox_full" } })
    expect(play.status).toBe(201)
  })

  it("refuses a card when the mailbox already holds 4 MiB", async () => {
    const mailbox = randomId()
    const cardsToFill = LIMITS.mailboxBytes / LIMITS.dataBytes.card
    for (let i = 0; i < cardsToFill; i++) await send(mailbox, "card", sealed(LIMITS.dataBytes.card))

    const full = await call(handleSendMessage, { mailbox, kind: "card", data: sealed(1) })

    expect(full).toMatchObject({ status: 409, body: { error: "mailbox_full" } })
  })

  it("replaces an unread profile with the newer one", async () => {
    const mailbox = randomId()
    await send(mailbox, "profile", sealed(50))
    const newer = await send(mailbox, "profile", sealed(50))

    const answer = await inbox(mailbox)

    expect(answer.mailboxes[0].messages.map((message) => message.data)).toEqual([newer.data])
  })

  it("keeps only the newest 500 plays in a mailbox", async () => {
    const mailbox = randomId()
    const sentIds: string[] = []
    while (sentIds.length < LIMITS.mailboxPlays + 1) {
      const batch = Math.min(LIMITS.rate.mailboxSends.limit, LIMITS.mailboxPlays + 1 - sentIds.length)
      for (let i = 0; i < batch; i++) sentIds.push((await send(mailbox, "play", sealed(20))).id)
      advanceClock(HOUR_MS)
    }

    const received: string[] = []
    let more = true
    while (more) {
      const page = (await inbox(mailbox)).mailboxes[0]
      received.push(...page.messages.map((message) => message.id))
      more = page.more
    }

    expect(received).toEqual(sentIds.slice(1))
  })

  it("allows 240 sends an hour into one mailbox", async () => {
    const mailbox = randomId()
    for (let i = 0; i < LIMITS.rate.mailboxSends.limit; i++) await send(mailbox, "play", sealed(20))

    const refused = await call(handleSendMessage, { mailbox, kind: "play", data: sealed(20) })
    const otherMailbox = await call(handleSendMessage, { mailbox: randomId(), kind: "play", data: sealed(20) })

    expect(refused).toMatchObject({ status: 429, body: { error: "rate_limited" } })
    expect(Number(refused.headers.get("Retry-After"))).toBeGreaterThan(0)
    expect(otherMailbox.status).toBe(201)
  })

  it("allows 16 MiB of data an hour from one address", async () => {
    const ip = randomIp()
    const cardsInBudget = LIMITS.rate.ipBytes.limit / LIMITS.dataBytes.card
    for (let i = 0; i < cardsInBudget; i++) await send(randomId(), "card", sealed(LIMITS.dataBytes.card), ip)

    const refused = await call(handleSendMessage, { mailbox: randomId(), kind: "card", data: sealed(1) }, { ip })

    expect(refused).toMatchObject({ status: 429, body: { error: "rate_limited" } })
  })
})

describe("POST /api/v1/messages/inbox", () => {
  it("returns waiting messages oldest first, with the server clock", async () => {
    const mailbox = randomId()
    const card = await send(mailbox, "card")
    const profile = await send(mailbox, "profile")

    const answer = await inbox(mailbox)

    expect(answer.now).toBeTypeOf("number")
    expect(answer.mailboxes).toEqual([
      {
        mailbox,
        messages: [
          { id: card.id, kind: "card", data: card.data, sentAt: expect.any(Number) },
          { id: profile.id, kind: "profile", data: profile.data, sentAt: expect.any(Number) },
        ],
        more: false,
      },
    ])
  })

  it("gives nothing for a wrong mailbox id", async () => {
    const mailbox = randomId()
    await send(mailbox, "card")
    await send(mailbox, "play")

    const answer = await inbox(randomId())

    expect(answer.mailboxes[0].messages).toEqual([])
    expect(answer.mailboxes[0].more).toBe(false)
  })

  it("answers several mailboxes in the order asked", async () => {
    const first = randomId()
    const second = randomId()
    await send(second, "card")

    const answer = await inbox(first, second)

    expect(answer.mailboxes.map((entry) => [entry.mailbox, entry.messages.length])).toEqual([
      [first, 0],
      [second, 1],
    ])
  })

  it("returns only messages after the cursor", async () => {
    const mailbox = randomId()
    const first = await send(mailbox, "card")
    const second = await send(mailbox, "card")
    const third = await send(mailbox, "card")

    const answer = await inbox({ mailbox, after: first.id })

    expect(answer.mailboxes[0].messages.map((message) => message.id)).toEqual([second.id, third.id])
  })

  it("deletes plays as it returns them, and keeps cards until ack", async () => {
    const mailbox = randomId()
    await send(mailbox, "play")
    const card = await send(mailbox, "card")

    const first = await inbox(mailbox)
    const second = await inbox(mailbox)

    expect(first.mailboxes[0].messages.map((message) => message.kind)).toEqual(["play", "card"])
    expect(second.mailboxes[0].messages.map((message) => message.id)).toEqual([card.id])
  })

  it("pages at 100 messages per mailbox", async () => {
    const mailbox = randomId()
    for (let i = 0; i < LIMITS.inboxMessagesPerMailbox + 5; i++) await send(mailbox, "card", sealed(10))

    const first = (await inbox(mailbox)).mailboxes[0]
    const last = first.messages.at(-1)?.id
    const rest = (await inbox({ mailbox, after: last })).mailboxes[0]

    expect(first.messages).toHaveLength(LIMITS.inboxMessagesPerMailbox)
    expect(first.more).toBe(true)
    expect(rest.messages).toHaveLength(5)
    expect(rest.more).toBe(false)
  })

  it("stops at about 1 MiB of data per answer", async () => {
    const mailbox = randomId()
    const other = randomId()
    const cardsInBudget = LIMITS.inboxBytes / LIMITS.dataBytes.card
    for (let i = 0; i < cardsInBudget + 2; i++) await send(mailbox, "card", sealed(LIMITS.dataBytes.card))
    await send(other, "card", sealed(10))

    const answer = await inbox(mailbox, other)

    expect(answer.mailboxes[0].messages).toHaveLength(cardsInBudget)
    expect(answer.mailboxes[0].more).toBe(true)
    expect(answer.mailboxes[1]).toMatchObject({ mailbox: other, messages: [], more: true })
  })

  it("drops a play after 24 hours and a card after 30 days", async () => {
    const mailbox = randomId()
    await send(mailbox, "play")
    await send(mailbox, "card")

    advanceClock(DAY_MS + 1000)
    const nextDay = await inbox(mailbox)
    advanceClock(29 * DAY_MS)
    const nextMonth = await inbox(mailbox)

    expect(nextDay.mailboxes[0].messages.map((message) => message.kind)).toEqual(["card"])
    expect(nextMonth.mailboxes[0].messages).toEqual([])
  })

  it("refuses more than 64 mailboxes, the same mailbox twice, and a bad cursor", async () => {
    const tooMany = await call(handleInbox, {
      mailboxes: Array.from({ length: LIMITS.inboxMailboxes + 1 }, () => ({ mailbox: randomId() })),
    })
    const mailbox = randomId()
    const twice = await call(handleInbox, { mailboxes: [{ mailbox }, { mailbox }] })
    const badCursor = await call(handleInbox, { mailboxes: [{ mailbox, after: "12a" }] })

    expect(tooMany).toMatchObject({ status: 400, body: { error: "bad_request", field: "mailboxes" } })
    expect(twice).toMatchObject({ status: 400, body: { error: "bad_request", field: "mailboxes" } })
    expect(badCursor).toMatchObject({ status: 400, body: { error: "bad_request", field: "mailboxes.0.after" } })
  })
})

describe("POST /api/v1/messages/ack", () => {
  it("deletes up to and including the cursor, in several mailboxes at once", async () => {
    const first = randomId()
    const second = randomId()
    await send(first, "card")
    const b = await send(first, "card")
    const c = await send(first, "card")
    const d = await send(second, "profile")

    const acked = await call(handleAck, {
      acks: [
        { mailbox: first, upTo: b.id },
        { mailbox: second, upTo: d.id },
      ],
    })
    const again = await call(handleAck, { acks: [{ mailbox: first, upTo: b.id }] })
    const answer = await inbox(first, second)

    expect(acked).toMatchObject({ status: 200, body: { deleted: 3 } })
    expect(again.body).toEqual({ deleted: 0 })
    expect(answer.mailboxes[0].messages.map((message) => message.id)).toEqual([c.id])
    expect(answer.mailboxes[1].messages).toEqual([])
  })

  it("deletes nothing through a wrong mailbox id", async () => {
    const mailbox = randomId()
    const card = await send(mailbox, "card")

    const acked = await call(handleAck, { acks: [{ mailbox: randomId(), upTo: card.id }] })

    expect(acked.body).toEqual({ deleted: 0 })
    expect((await inbox(mailbox)).mailboxes[0].messages).toHaveLength(1)
  })
})

describe("POST /api/v1/messages/delete", () => {
  it("lets the sender take back a card the friend has not acknowledged", async () => {
    const mailbox = randomId()
    const card = await send(mailbox, "card")

    const wrongMailbox = await call(handleDeleteMessages, { mailbox: randomId(), ids: [card.id] })
    const undone = await call(handleDeleteMessages, { mailbox, ids: [card.id] })
    const again = await call(handleDeleteMessages, { mailbox, ids: [card.id] })

    expect(wrongMailbox.body).toEqual({ deleted: 0 })
    expect(undone).toMatchObject({ status: 200, body: { deleted: 1 } })
    expect(again.body).toEqual({ deleted: 0 })
    expect((await inbox(mailbox)).mailboxes[0].messages).toEqual([])
  })

  it("refuses an empty list and more than 100 ids", async () => {
    const empty = await call(handleDeleteMessages, { mailbox: randomId(), ids: [] })
    const tooMany = await call(handleDeleteMessages, {
      mailbox: randomId(),
      ids: Array.from({ length: LIMITS.deleteIds + 1 }, (_, i) => String(i + 1)),
    })

    expect(empty).toMatchObject({ status: 400, body: { field: "ids" } })
    expect(tooMany).toMatchObject({ status: 400, body: { field: "ids" } })
  })
})

describe("POST /api/v1/mailboxes/delete", () => {
  it("empties both directions of a friendship", async () => {
    const toFriend = randomId()
    const fromFriend = randomId()
    const keep = randomId()
    await send(toFriend, "card")
    await send(toFriend, "play")
    await send(fromFriend, "profile")
    await send(keep, "card")

    const dropped = await call(handleDeleteMailboxes, { mailboxes: [toFriend, fromFriend] })
    const answer = await inbox(toFriend, fromFriend, keep)

    expect(dropped).toMatchObject({ status: 200, body: { deleted: 3 } })
    expect(answer.mailboxes.map((entry) => entry.messages.length)).toEqual([0, 0, 1])
  })
})
