import { type ChildProcess, spawn } from "node:child_process"
import { randomBytes } from "node:crypto"
import { request as httpRequest } from "node:http"
import { afterAll, beforeAll, describe, expect, it } from "vitest"
import { LIMITS } from "../src/lib/limits"

const PORT = 3000 + Math.floor(Math.random() * 2000)
const BASE = `http://127.0.0.1:${PORT}`
let server: ChildProcess

function randomId() {
  return randomBytes(32).toString("base64url")
}

/** Runs `call` `count` times, 50 at a time, and gives back each answer in order. */
async function inBatches<T>(count: number, call: () => Promise<T>) {
  const answers: T[] = []
  while (answers.length < count) {
    const batch = Math.min(50, count - answers.length)
    answers.push(...(await Promise.all(Array.from({ length: batch }, call))))
  }
  return answers
}

/** A raw GET with a chosen Host header, as each public host name reaches the one Railway service. */
function getWithHost(path: string, host: string) {
  return new Promise<{ status: number; headers: Record<string, unknown>; body: string }>((resolve, reject) => {
    const req = httpRequest({ host: "127.0.0.1", port: PORT, path, headers: { Host: host } }, (res) => {
      let body = ""
      res.on("data", (chunk) => (body += chunk))
      res.on("end", () => resolve({ status: res.statusCode ?? 0, headers: res.headers, body }))
    })
    req.on("error", reject)
    req.end()
  })
}

async function post(path: string, body: unknown) {
  const response = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Real-IP": "10.1.2.3" },
    body: JSON.stringify(body),
  })
  return { status: response.status, headers: response.headers, body: await response.json() }
}

beforeAll(async () => {
  server = spawn(process.execPath, ["node_modules/srvx/bin/srvx.mjs", "--prod", "-s", "../client", "dist/server/server.js"], {
    env: { ...process.env, PORT: String(PORT), NODE_ENV: "production" },
    stdio: "inherit",
  })
  for (let tries = 0; tries < 100; tries++) {
    const up = await fetch(`${BASE}/health`).then((response) => response.ok, () => false)
    if (up) return
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
  throw new Error("the built server did not start")
})

afterAll(async () => {
  if (!server) return
  // Wait for the graceful stop, so the server never sees the test database go away under it.
  const exited = new Promise((resolve) => server.once("exit", resolve))
  server.kill()
  await exited
})

describe("the built server", () => {
  it("answers /health", async () => {
    const response = await fetch(`${BASE}/health`)

    expect(response.status).toBe(200)
    expect(await response.json()).toEqual({ ok: true })
  })

  it.each(["kjb-huddle.up.railway.app", "huddle.kjb.mostafade.com"])(
    "serves assetlinks.json on %s with no redirect",
    async (host) => {
      const response = await getWithHost("/.well-known/assetlinks.json", host)

      expect(response.status).toBe(200)
      expect(response.headers["content-type"]).toBe("application/json")
      expect(JSON.parse(response.body)[0].target.package_name).toBe("app.lyrify")
    },
  )

  it("serves the invite page with its security headers", async () => {
    const response = await fetch(`${BASE}/i`)
    const html = await response.text()

    expect(response.status).toBe(200)
    expect(response.headers.get("content-type")).toContain("text/html")
    expect(html).toContain("A friend invited you to KJB")
    expect(response.headers.get("content-security-policy")).toContain("frame-ancestors 'none'")
    expect(response.headers.get("x-frame-options")).toBe("DENY")
    expect(response.headers.get("referrer-policy")).toBe("no-referrer")
  })

  it("carries an invite and a card between two phones", async () => {
    const invite = { invite: randomId(), owner: randomId() }
    const mailbox = randomId()

    const created = await post("/api/v1/invites/create", invite)
    const accepted = await post("/api/v1/invites/accept", { invite: invite.invite, data: "aGVsbG8" })
    const status = await post("/api/v1/invites/status", { owner: invite.owner })
    const sent = await post("/api/v1/messages/send", { mailbox, kind: "card", data: "c29uZw" })
    const inbox = await post("/api/v1/messages/inbox", { mailboxes: [{ mailbox }] })
    const acked = await post("/api/v1/messages/ack", { acks: [{ mailbox, upTo: sent.body.id }] })

    expect(created.status).toBe(201)
    expect(accepted.status).toBe(200)
    expect(status.body).toMatchObject({ state: "accepted", data: "aGVsbG8" })
    expect(sent.status).toBe(201)
    expect(inbox.body.mailboxes[0].messages[0]).toMatchObject({ id: sent.body.id, kind: "card", data: "c29uZw" })
    expect(acked.body).toEqual({ deleted: 1 })
    expect(sent.headers.get("cache-control")).toBe("no-store")
    expect(sent.headers.get("x-content-type-options")).toBe("nosniff")
  })

  it("answers API errors as JSON", async () => {
    const response = await fetch(`${BASE}/api/v1/messages/send`, {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body: "{}",
    })

    expect(response.status).toBe(415)
    expect(await response.json()).toEqual({ error: "unsupported_media_type" })
  })

  it("serves the scripts and styles the invite page loads", async () => {
    const html = await fetch(`${BASE}/i`).then((response) => response.text())
    const assets = [...html.matchAll(/(?:src|href)="(\/assets\/[^"]+)"/g)].map((match) => match[1])

    expect(assets.some((asset) => asset.endsWith(".js"))).toBe(true)
    expect(assets.some((asset) => asset.endsWith(".css"))).toBe(true)
    for (const asset of new Set(assets)) {
      const response = await fetch(`${BASE}${asset}`)
      expect(response.status, asset).toBe(200)
    }
  })

  it("answers anything but a POST on an API path with 405 and JSON", async () => {
    const response = await fetch(`${BASE}/api/v1/messages/send`)

    expect(response.status).toBe(405)
    expect(response.headers.get("allow")).toBe("POST")
    expect(response.headers.get("cache-control")).toBe("no-store")
    expect(await response.json()).toEqual({ error: "method_not_allowed" })
  })

  it("answers an unknown API path with 404 and JSON", async () => {
    const response = await post("/api/v1/nothing", {})

    expect(response.status).toBe(404)
    expect(response.body).toEqual({ error: "not_found" })
  })

  it("limits API calls and pages per address in separate windows, and never /health or assetlinks.json", async () => {
    const headers = { "X-Real-IP": "10.200.0.1" }
    const ack = JSON.stringify({ acks: [{ mailbox: randomId(), upTo: "1" }] })
    const callApi = () =>
      fetch(`${BASE}/api/v1/messages/ack`, {
        method: "POST",
        headers: { ...headers, "Content-Type": "application/json" },
        body: ack,
      }).then((response) => response.status)
    const getPage = () => fetch(`${BASE}/`, { headers }).then((response) => response.status)

    const apiCalls = await inBatches(LIMITS.rate.ip.limit, callApi)
    const apiOver = [await callApi(), (await fetch(`${BASE}/api/v1/messages/send`, { headers })).status]
    const pageAfterApi = await getPage()
    const pages = await inBatches(LIMITS.rate.pages.limit - 1, getPage)
    const pageOver = await getPage()
    const free = await inBatches(50, () =>
      Promise.all([
        fetch(`${BASE}/health`, { headers }).then((response) => response.status),
        fetch(`${BASE}/.well-known/assetlinks.json`, { headers }).then((response) => response.status),
      ]),
    )

    expect(apiCalls.every((status) => status === 200)).toBe(true)
    expect(apiOver).toEqual([429, 429])
    expect(pageAfterApi).toBe(200)
    expect(pages.every((status) => status === 200)).toBe(true)
    expect(pageOver).toBe(429)
    expect(free.flat().every((status) => status === 200)).toBe(true)
  })

  it("answers an unknown page with 404 and its own page", async () => {
    const response = await fetch(`${BASE}/nothing-here`)

    expect(response.status).toBe(404)
    expect(await response.text()).toContain("This page does not exist")
  })
})
