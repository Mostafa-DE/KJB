import { afterEach, describe, expect, it } from "vitest"
import { db } from "@/db"
import { invites, messages } from "@/db/schema"
import { deleteExpiredRows } from "@/data-access/store"
import { handleAcceptInvite } from "@/routes/api/v1/invites/accept"
import { handleCreateInvite } from "@/routes/api/v1/invites/create"
import { handleInbox } from "@/routes/api/v1/messages/inbox"
import { handleSendMessage } from "@/routes/api/v1/messages/send"
import { advanceClock, call, randomId, sealed } from "@/tests/api"

const DAY_MS = 24 * 60 * 60 * 1000

describe("deleteExpiredRows", () => {
  it("deletes expired messages and invites and keeps the rest", async () => {
    await call(handleSendMessage, { mailbox: randomId(), kind: "play", data: sealed(20) })
    await call(handleSendMessage, { mailbox: randomId(), kind: "card", data: sealed(20) })
    await call(handleCreateInvite, { invite: randomId(), owner: randomId() })
    const accepted = { invite: randomId(), owner: randomId() }
    await call(handleCreateInvite, accepted)

    advanceClock(6 * DAY_MS)
    await call(handleAcceptInvite, { invite: accepted.invite, data: sealed(20) })
    advanceClock(2 * DAY_MS)
    const deleted = await deleteExpiredRows(new Date())

    expect(deleted).toEqual({ messages: 1, invites: 1 })
    expect(await db.select({ kind: messages.kind }).from(messages)).toEqual([{ kind: "card" }])
    expect(await db.$count(invites)).toBe(1)
  })
})

describe("the storage guard", () => {
  afterEach(() => {
    delete process.env.HUDDLE_MAX_STORE_MB
  })

  it("refuses writes while the store is over its size, and still serves reads", async () => {
    process.env.HUDDLE_MAX_STORE_MB = "0"
    advanceClock(DAY_MS)

    const send = await call(handleSendMessage, { mailbox: randomId(), kind: "card", data: sealed(20) })
    const invite = await call(handleCreateInvite, { invite: randomId(), owner: randomId() })
    const read = await call(handleInbox, { mailboxes: [{ mailbox: randomId() }] })

    expect(send).toMatchObject({ status: 503, body: { error: "storage_full" } })
    expect(invite).toMatchObject({ status: 503, body: { error: "storage_full" } })
    expect(read.status).toBe(200)

    delete process.env.HUDDLE_MAX_STORE_MB
    advanceClock(DAY_MS)
    const later = await call(handleSendMessage, { mailbox: randomId(), kind: "card", data: sealed(20) })

    expect(later.status).toBe(201)
  })
})
