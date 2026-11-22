import { afterEach, describe, expect, it, vi } from "vitest"
import { db } from "@/db"
import { invites, messages } from "@/db/schema"
import { deleteExpiredRows, storeIsFull } from "@/data-access/store"
import { LIMITS } from "@/lib/limits"
import { handleAcceptInvite } from "@/routes/api/v1/invites/accept"
import { handleCreateInvite } from "@/routes/api/v1/invites/create"
import { handleInbox } from "@/routes/api/v1/messages/inbox"
import { handleSendMessage } from "@/routes/api/v1/messages/send"
import { advanceClock, call, randomId, sealed } from "@/tests/api"

const DAY_MS = 24 * 60 * 60 * 1000
const MIB = 1024 * 1024

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

  async function sendCard() {
    return call(handleSendMessage, { mailbox: randomId(), kind: "card", data: sealed(LIMITS.dataBytes.card) })
  }

  it("refuses writes once the stored data passes its size, still serves reads, and keeps its answer 5 minutes", async () => {
    process.env.HUDDLE_MAX_STORE_MB = "1"
    for (let i = 0; i < MIB / LIMITS.dataBytes.card; i++) expect((await sendCard()).status).toBe(201)

    advanceClock(LIMITS.storeCheckMs)
    const send = await sendCard()
    const invite = await call(handleCreateInvite, { invite: randomId(), owner: randomId() })
    const read = await call(handleInbox, { mailboxes: [{ mailbox: randomId() }] })
    await db.delete(messages)
    const stillFull = await sendCard()
    advanceClock(LIMITS.storeCheckMs)
    const later = await sendCard()

    expect(send).toMatchObject({ status: 503, body: { error: "storage_full" } })
    expect(invite).toMatchObject({ status: 503, body: { error: "storage_full" } })
    expect(read.status).toBe(200)
    expect(stillFull.status).toBe(503)
    expect(later.status).toBe(201)
  })

  it("measures once for writes that arrive together", async () => {
    advanceClock(LIMITS.storeCheckMs)
    const measured = vi.spyOn(db, "execute")

    await Promise.all([storeIsFull(), storeIsFull(), storeIsFull()])

    expect(measured).toHaveBeenCalledTimes(1)
    measured.mockRestore()
  })
})
