import { describe, expect, it } from "vitest"
import { db } from "@/db"
import { invites } from "@/db/schema"
import { LIMITS } from "@/lib/limits"
import { advanceClock, call, randomId, randomIp, sealed } from "@/tests/api"
import { handleAcceptInvite } from "./accept"
import { handleCreateInvite } from "./create"
import { handleDeleteInvite } from "./delete"
import { handleInviteStatus } from "./status"

const DAY_MS = 24 * 60 * 60 * 1000

async function createdInvite() {
  const ids = { invite: randomId(), owner: randomId() }
  const created = await call(handleCreateInvite, ids)
  expect(created.status).toBe(201)
  return ids
}

describe("POST /api/v1/invites/create", () => {
  it("creates a waiting invite that expires in 7 days", async () => {
    const before = Date.now()
    const created = await call(handleCreateInvite, { invite: randomId(), owner: randomId() })

    expect(created.status).toBe(201)
    expect(created.body.expiresAt).toBeGreaterThanOrEqual(before + 7 * DAY_MS)
    expect(created.body.expiresAt).toBeLessThanOrEqual(Date.now() + 7 * DAY_MS)
  })

  it("refuses an invite id or an owner id already in use", async () => {
    const ids = await createdInvite()

    const sameInvite = await call(handleCreateInvite, { invite: ids.invite, owner: randomId() })
    const sameOwner = await call(handleCreateInvite, { invite: randomId(), owner: ids.owner })

    expect(sameInvite).toMatchObject({ status: 409, body: { error: "invite_exists" } })
    expect(sameOwner).toMatchObject({ status: 409, body: { error: "invite_exists" } })
  })

  it("refuses ids that are not 43 base64url characters", async () => {
    const short = await call(handleCreateInvite, { invite: randomId().slice(1), owner: randomId() })
    const badCharacters = await call(handleCreateInvite, { invite: randomId(), owner: `${randomId().slice(1)}+` })

    expect(short).toMatchObject({ status: 400, body: { error: "bad_request", field: "invite" } })
    expect(badCharacters).toMatchObject({ status: 400, body: { error: "bad_request", field: "owner" } })
  })

  it("refuses the same id as invite and owner, so the link never carries the owner's rights", async () => {
    const id = randomId()

    const created = await call(handleCreateInvite, { invite: id, owner: id })

    expect(created).toMatchObject({ status: 400, body: { error: "bad_request", field: "owner" } })
  })

  it("stores only hashes of the ids", async () => {
    const ids = await createdInvite()

    const rows = await db.select().from(invites)

    expect(rows).toHaveLength(1)
    expect(JSON.stringify(rows)).not.toContain(ids.invite)
    expect(JSON.stringify(rows)).not.toContain(ids.owner)
  })

  it("allows 30 invites an hour from one address", async () => {
    const ip = randomIp()
    for (let i = 0; i < LIMITS.rate.inviteCreate.limit; i++) {
      const created = await call(handleCreateInvite, { invite: randomId(), owner: randomId() }, { ip })
      expect(created.status).toBe(201)
    }

    const refused = await call(handleCreateInvite, { invite: randomId(), owner: randomId() }, { ip })
    const otherAddress = await call(handleCreateInvite, { invite: randomId(), owner: randomId() })

    expect(refused).toMatchObject({ status: 429, body: { error: "rate_limited" } })
    expect(Number(refused.headers.get("Retry-After"))).toBeGreaterThan(0)
    expect(Number(refused.headers.get("Retry-After"))).toBeLessThanOrEqual(3600)
    expect(otherAddress.status).toBe(201)
  })
})

describe("POST /api/v1/invites/accept", () => {
  it("lets the first friend accept and hands their data to the owner", async () => {
    const ids = await createdInvite()
    const hello = sealed(300)

    const accepted = await call(handleAcceptInvite, { invite: ids.invite, data: hello })
    const status = await call(handleInviteStatus, { owner: ids.owner })

    expect(accepted.status).toBe(200)
    expect(accepted.body.acceptedAt).toBeTypeOf("number")
    expect(status).toMatchObject({
      status: 200,
      body: { state: "accepted", data: hello, acceptedAt: accepted.body.acceptedAt },
    })
  })

  it("accepts only once", async () => {
    const ids = await createdInvite()
    const first = sealed(100)
    await call(handleAcceptInvite, { invite: ids.invite, data: first })

    const second = await call(handleAcceptInvite, { invite: ids.invite, data: sealed(100) })
    const status = await call(handleInviteStatus, { owner: ids.owner })

    expect(second).toMatchObject({ status: 409, body: { error: "already_accepted" } })
    expect(status.body.data).toBe(first)
  })

  it("answers the same accept again with the first acceptedAt, so a lost answer is safe to retry", async () => {
    const ids = await createdInvite()
    const hello = sealed(300)
    const first = await call(handleAcceptInvite, { invite: ids.invite, data: hello })

    advanceClock(60 * 1000)
    const again = await call(handleAcceptInvite, { invite: ids.invite, data: hello })
    const other = await call(handleAcceptInvite, { invite: ids.invite, data: sealed(300) })
    const status = await call(handleInviteStatus, { owner: ids.owner })

    expect(again).toMatchObject({ status: 200, body: { acceptedAt: first.body.acceptedAt } })
    expect(other).toMatchObject({ status: 409, body: { error: "already_accepted" } })
    expect(status.body).toMatchObject({ data: hello, acceptedAt: first.body.acceptedAt })
  })

  it("lets only one of two friends accepting at once win", async () => {
    const ids = await createdInvite()

    const answers = await Promise.all([
      call(handleAcceptInvite, { invite: ids.invite, data: sealed(50) }),
      call(handleAcceptInvite, { invite: ids.invite, data: sealed(50) }),
    ])

    expect(answers.map((answer) => answer.status).sort()).toEqual([200, 409])
  })

  it("does not know an invite nobody created", async () => {
    const accepted = await call(handleAcceptInvite, { invite: randomId(), data: sealed(10) })

    expect(accepted).toMatchObject({ status: 404, body: { error: "not_found" } })
  })

  it("refuses data over 4,096 bytes", async () => {
    const ids = await createdInvite()

    const fits = await call(handleAcceptInvite, { invite: ids.invite, data: sealed(LIMITS.acceptBytes) })
    const other = await createdInvite()
    const tooBig = await call(handleAcceptInvite, { invite: other.invite, data: sealed(LIMITS.acceptBytes + 1) })

    expect(fits.status).toBe(200)
    expect(tooBig).toMatchObject({ status: 413, body: { error: "too_large" } })
  })

  it("allows 30 accepts an hour from one address", async () => {
    const ip = randomIp()
    for (let i = 0; i < LIMITS.rate.inviteAccept.limit; i++) {
      const accepted = await call(handleAcceptInvite, { invite: randomId(), data: sealed(10) }, { ip })
      expect(accepted.status).toBe(404)
    }

    const refused = await call(handleAcceptInvite, { invite: randomId(), data: sealed(10) }, { ip })

    expect(refused).toMatchObject({ status: 429, body: { error: "rate_limited" } })
  })

  it("refuses an invite that waited more than 7 days", async () => {
    const ids = await createdInvite()

    advanceClock(7 * DAY_MS + 1000)
    const accepted = await call(handleAcceptInvite, { invite: ids.invite, data: sealed(10) })
    const status = await call(handleInviteStatus, { owner: ids.owner })

    expect(accepted).toMatchObject({ status: 404, body: { error: "not_found" } })
    expect(status).toMatchObject({ status: 404, body: { error: "not_found" } })
  })

  it("keeps an accepted invite for 7 days after the accept", async () => {
    const ids = await createdInvite()
    advanceClock(6 * DAY_MS)
    await call(handleAcceptInvite, { invite: ids.invite, data: sealed(10) })

    advanceClock(6 * DAY_MS)
    const stillThere = await call(handleInviteStatus, { owner: ids.owner })
    advanceClock(DAY_MS + 1000)
    const gone = await call(handleInviteStatus, { owner: ids.owner })

    expect(stillThere).toMatchObject({ status: 200, body: { state: "accepted" } })
    expect(gone).toMatchObject({ status: 404, body: { error: "not_found" } })
  })
})

describe("POST /api/v1/invites/status", () => {
  it("shows a waiting invite with its expiry", async () => {
    const ids = await createdInvite()

    const status = await call(handleInviteStatus, { owner: ids.owner })

    expect(status.status).toBe(200)
    expect(status.body).toEqual({ state: "waiting", expiresAt: expect.any(Number) })
  })

  it("tells nothing to someone who only knows the link", async () => {
    const ids = await createdInvite()
    await call(handleAcceptInvite, { invite: ids.invite, data: sealed(10) })

    const status = await call(handleInviteStatus, { owner: ids.invite })

    expect(status).toMatchObject({ status: 404, body: { error: "not_found" } })
  })
})

describe("POST /api/v1/invites/delete", () => {
  it("deletes with the owner id only, and is safe to repeat", async () => {
    const ids = await createdInvite()

    const byLinkHolder = await call(handleDeleteInvite, { owner: ids.invite })
    const byOwner = await call(handleDeleteInvite, { owner: ids.owner })
    const again = await call(handleDeleteInvite, { owner: ids.owner })
    const status = await call(handleInviteStatus, { owner: ids.owner })

    expect(byLinkHolder).toMatchObject({ status: 200, body: { deleted: 0 } })
    expect(byOwner).toMatchObject({ status: 200, body: { deleted: 1 } })
    expect(again).toMatchObject({ status: 200, body: { deleted: 0 } })
    expect(status.status).toBe(404)
  })
})
